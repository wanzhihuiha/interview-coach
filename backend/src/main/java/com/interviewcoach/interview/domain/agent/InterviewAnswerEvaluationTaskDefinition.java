package com.interviewcoach.interview.domain.agent;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.interviewcoach.common.llm.LlmDataBlock;
import com.interviewcoach.common.llm.LlmDataSource;
import com.interviewcoach.common.llm.LlmExecutionResult;
import com.interviewcoach.common.llm.LlmFailureType;
import com.interviewcoach.common.llm.LlmTaskDefinition;
import com.interviewcoach.common.llm.LlmTaskType;
import com.interviewcoach.interview.domain.entity.InterviewPhase;
import com.interviewcoach.interview.domain.model.EvaluationResult;
import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 面试回答评估任务定义：固定服务端评估要求，并严格校验模型只能返回评分和简短评价。
 */
@Component
@RequiredArgsConstructor
public class InterviewAnswerEvaluationTaskDefinition
        implements LlmTaskDefinition<InterviewAnswerEvaluationTaskDefinition.Parameters, EvaluationResult> {

    /** 限制模型原始响应大小，避免把异常长文本交给 JSON 解析器继续处理。 */
    private static final int MAX_RAW_RESPONSE_LENGTH = 8_000;

    /** 限制最终可进入业务对象的评价长度。 */
    private static final int MAX_COMMENT_CHARACTERS = 300;

    /** 模型响应必须完整包含且只能包含这些字段。 */
    private static final Set<String> RESPONSE_FIELDS = Set.of(
            "overall",
            "technicalDepth",
            "technicalBreadth",
            "practicalExperience",
            "expression",
            "learningAbility",
            "comment");

    /** 整体评价字段允许使用的固定枚举值。 */
    private static final Set<String> ALLOWED_OVERALL_VALUES = Set.of(
            "优秀", "良好", "一般", "较差", "很差");

    /** 一旦模型尝试返回这些服务端流程字段，立即拒绝结果且不进行格式重试。 */
    private static final Set<String> FORBIDDEN_FLOW_FIELDS = Set.of(
            "suggestedNextDepth",
            "keyEvent",
            "phase",
            "topic",
            "nextAction",
            "shouldSwitchTopic",
            "shouldEnd");

    private final ObjectMapper objectMapper;

    @Override
    public LlmTaskType taskType() {
        return LlmTaskType.INTERVIEW_ANSWER_EVALUATION;
    }

    @Override
    public Class<Parameters> parametersType() {
        return Parameters.class;
    }

    @Override
    public Class<EvaluationResult> responseType() {
        return EvaluationResult.class;
    }

    /**
     * 只把服务端持有的面试环节和题目深度写入可信系统提示词。
     * 问题、回答、岗位和主题等可能受污染的文本不会进入这里。
     */
    @Override
    public String buildSystemPrompt(Parameters parameters) {
        String depthDescription = parameters.questionDepth() == null
                ? ""
                : "，当前问题深度固定为 L" + parameters.questionDepth();
        return "你是一名专业的面试评估官，只负责评估候选人回答。"
                + "当前面试环节由服务端固定为“" + parameters.phase().getDisplayName() + "”"
                + depthDescription + "。"
                + "面试阶段、主题、下一题深度、题目计数、主题切换和是否结束均由服务端决定，"
                + "你不得返回或改变这些内容。";
    }

    /**
     * 固定模型的评估目标和唯一允许的输出结构，业务调用方不能临时扩展返回字段。
     */
    @Override
    public String buildTaskInstruction(Parameters parameters) {
        return "读取 DATA_ONLY_JSON 中的 question、answer 以及可选的岗位和主题数据，"
                + "只评价 answer 对 question 的回答质量。"
                + "\n只输出一个 JSON 对象，必须且只能包含以下字段："
                + "overall、technicalDepth、technicalBreadth、practicalExperience、expression、"
                + "learningAbility、comment。"
                + "\noverall 只能是优秀、良好、一般、较差、很差之一；"
                + "五项分数必须是 0-100 的整数；comment 必须是 1-"
                + MAX_COMMENT_CHARACTERS + " 个 Unicode 字符。"
                + "\n不得返回 suggestedNextDepth、keyEvent、phase、topic、nextAction、"
                + "shouldSwitchTopic、shouldEnd 或任何其他字段。";
    }

    /**
     * 严格拒绝代码围栏、缺失字段、额外字段、重复字段、非整数分数和尾随 JSON 内容。
     */
    @Override
    public LlmExecutionResult<EvaluationResult> parseResponse(String rawResponse) {
        // 第一道边界：空响应属于格式错误，异常长响应属于内容不可信，均不进入 JSON 映射。
        if (rawResponse == null) {
            return LlmExecutionResult.failure(LlmFailureType.INVALID_RESPONSE_FORMAT);
        }
        if (rawResponse.length() > MAX_RAW_RESPONSE_LENGTH) {
            return LlmExecutionResult.failure(LlmFailureType.INVALID_RESPONSE_CONTENT);
        }

        JsonNode root;
        try (JsonParser parser = objectMapper.createParser(rawResponse)) {
            // 只接受一个完整 JSON 对象；重复字段和对象后的尾随内容都可能掩盖真实值，必须拒绝。
            parser.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
            root = objectMapper.readTree(parser);
            if (parser.nextToken() != null) {
                return LlmExecutionResult.failure(LlmFailureType.INVALID_RESPONSE_FORMAT);
            }
        } catch (IOException e) {
            return LlmExecutionResult.failure(LlmFailureType.INVALID_RESPONSE_FORMAT);
        }
        if (root == null || !root.isObject()) {
            return LlmExecutionResult.failure(LlmFailureType.INVALID_RESPONSE_FORMAT);
        }
        // 流程字段表示模型越过了“只评分”的权限边界，因此按内容违规处理，不允许原请求重试。
        if (FORBIDDEN_FLOW_FIELDS.stream().anyMatch(root::has)) {
            return LlmExecutionResult.failure(LlmFailureType.INVALID_RESPONSE_CONTENT);
        }
        // 字段数量和必填字段同时核对，既拒绝缺失字段，也拒绝任何未列入白名单的额外字段。
        if (root.size() != RESPONSE_FIELDS.size()
                || RESPONSE_FIELDS.stream().anyMatch(field -> !root.has(field))) {
            return LlmExecutionResult.failure(LlmFailureType.INVALID_RESPONSE_FORMAT);
        }

        JsonNode overallNode = root.get("overall");
        JsonNode commentNode = root.get("comment");
        if (!overallNode.isTextual() || !commentNode.isTextual()) {
            return LlmExecutionResult.failure(LlmFailureType.INVALID_RESPONSE_FORMAT);
        }
        String overall = overallNode.textValue().strip();
        String comment = commentNode.textValue().strip();
        // 等级使用白名单，评价不能为空且按 Unicode 字符计数，避免中文和表情被错误按字节截断。
        if (!ALLOWED_OVERALL_VALUES.contains(overall)
                || comment.isBlank()
                || comment.codePointCount(0, comment.length()) > MAX_COMMENT_CHARACTERS) {
            return LlmExecutionResult.failure(LlmFailureType.INVALID_RESPONSE_CONTENT);
        }

        Integer technicalDepth = readScore(root, "technicalDepth");
        Integer technicalBreadth = readScore(root, "technicalBreadth");
        Integer practicalExperience = readScore(root, "practicalExperience");
        Integer expression = readScore(root, "expression");
        Integer learningAbility = readScore(root, "learningAbility");
        // 五项分数缺一不可；任一项类型或范围不合法时，整份评估都不可用，不拼接部分结果。
        if (technicalDepth == null || technicalBreadth == null
                || practicalExperience == null || expression == null
                || learningAbility == null) {
            return LlmExecutionResult.failure(LlmFailureType.INVALID_RESPONSE_CONTENT);
        }

        EvaluationResult result = new EvaluationResult();
        result.setOverall(overall);
        result.setTechnicalDepth(technicalDepth);
        result.setTechnicalBreadth(technicalBreadth);
        result.setPracticalExperience(practicalExperience);
        result.setExpression(expression);
        result.setLearningAbility(learningAbility);
        result.setComment(comment);
        return LlmExecutionResult.success(result);
    }

    /**
     * 把模型生成的文本字段重新包装为不可信数据，交给安全网关进行输出风险复检。
     * 数值字段已经在 {@link #parseResponse(String)} 中完成类型和范围校验。
     */
    @Override
    public List<LlmDataBlock> buildResponseDataBlocks(EvaluationResult response) {
        return List.of(
                new LlmDataBlock(
                        "evaluation-overall", LlmDataSource.MODEL_DERIVED_CONTENT, response.getOverall()),
                new LlmDataBlock(
                        "evaluation-comment", LlmDataSource.MODEL_DERIVED_CONTENT, response.getComment()));
    }

    /**
     * 读取一个 0～100 的 JSON 整数；字符串、布尔值、小数和越界值均返回 {@code null}。
     */
    private Integer readScore(JsonNode root, String fieldName) {
        JsonNode scoreNode = root.get(fieldName);
        if (scoreNode == null || !scoreNode.isIntegralNumber() || !scoreNode.canConvertToInt()) {
            return null;
        }
        int score = scoreNode.intValue();
        return score >= 0 && score <= 100 ? score : null;
    }

    /**
     * 只保存服务端状态；问题、回答、岗位和主题文本必须放入 DATA_ONLY 数据块。
     *
     * @param phase 服务端状态机确定的当前面试环节
     * @param questionDepth 服务端维护的当前题目深度，可为空，否则只能为 L1～L5
     */
    public record Parameters(InterviewPhase phase, Integer questionDepth) {

        public Parameters {
            // 在构造入口收紧服务端参数，避免无效状态被拼进可信提示词。
            Objects.requireNonNull(phase, "回答评估缺少面试环节");
            if (questionDepth != null && (questionDepth < 1 || questionDepth > 5)) {
                throw new IllegalArgumentException("回答评估问题深度必须在 L1-L5 之间");
            }
        }
    }
}
