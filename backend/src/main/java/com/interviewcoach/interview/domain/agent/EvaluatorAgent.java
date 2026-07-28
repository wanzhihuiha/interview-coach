package com.interviewcoach.interview.domain.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.interviewcoach.common.security.agent.AgentContext;
import com.interviewcoach.common.security.agent.AgentType;
import com.interviewcoach.interview.application.dto.QuestionBankItem;
import com.interviewcoach.interview.domain.model.EvaluationResult;
import com.interviewcoach.interview.domain.model.EvaluationSignal;
import com.interviewcoach.interview.domain.model.InterviewContext;
import com.interviewcoach.interview.infrastructure.tool.QuestionBankTool;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 评估者 Agent：把当前问题、回答和面试上下文交给模型评估，并解析为后续流程使用的信号来源。
 *
 * <p>上游由 {@link CoordinatorAgent} 在 Skill 要求评分时调用。模型调用异常、响应为空或缺少评估主体时
 * 返回 {@code null}，协调器随后使用本地规则兜底；JSON 解析异常则会进入正则宽松解析，并返回字段可能不完整的
 * 非空结果，因此不会再触发协调器的本地规则。模型正常返回后，本组件还会尝试把当前问题写入临时题库，
 * 供后续审核使用。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EvaluatorAgent {

    private final LlmInterviewService llmService;
    private final QuestionBankTool questionBankTool;
    private final ObjectMapper objectMapper;

    /**
     * 评估当前回答，并把可用结果交给协调器继续决策。
     *
     * <p>先组装岗位、环节、主题和本轮问答，再调用模型并解析 JSON。模型调用等外层流程抛出异常时返回
     * {@code null}；JSON 解析异常由解析方法内部转成宽松结果。模型正常返回后会尝试将本轮问题写入临时题库，
     * 即使解析结果为 {@code null} 也会写入；该写入自身会吞掉异常，不影响评估结果。</p>
     */
    public EvaluationResult evaluate(InterviewContext context, String question, String answer) {
        return AgentContext.runAs(AgentType.EVALUATOR, () -> {
            // 1. 把本轮问答与必要的面试上下文整理为模型可判断的输入。
            String system = "你是一个专业的面试评估官，负责评估候选人的回答质量。只输出 JSON。";
            StringBuilder user = new StringBuilder();
            user.append("**面试上下文**\n");
            if (context.getPositionProfile() != null && context.getPositionProfile().getBasicInfo() != null) {
                user.append("- 岗位：").append(context.getPositionProfile().getBasicInfo().getTitle()).append("\n");
            }
            user.append("- 当前环节：").append(context.getCurrentPhase().getDisplayName()).append("\n");
            if (context.getCurrentTopicName() != null) {
                user.append("- 当前主题：").append(context.getCurrentTopicName()).append("\n");
            }
            user.append("- 问题：").append(question).append("\n");
            user.append("- 候选人回答：").append(answer).append("\n");
            user.append("\n评估要求：\n");
            user.append("1. 判断回答质量：优秀/良好/一般/较差/很差\n");
            user.append("2. 评估各维度得分（0-100）：technicalDepth、technicalBreadth、practicalExperience、expression、learningAbility\n");
            user.append("3. 给出下一问题的建议深度（1-5）\n");
            user.append("4. 如有突出亮点或明显不足，keyEvent 填 EXCELLENT/STRUGGLED，否则 null\n");
            user.append("输出格式：{\"assessment\":{\"overall\":\"...\",\"technicalDepth\":80,...}}");

            try {
                // 2. 调用模型并解析结果；空响应或缺少 assessment 时解析结果为 null。
                String response = llmService.chat(system, user.toString());
                EvaluationResult result = parseEvaluation(response);
                // 3. 临时题库写入是隐藏副作用，即使评估结果为 null 也会尝试执行。
                saveQuestionToTemporaryRag(context, question);
                return result;
            } catch (Exception e) {
                log.warn("[EvaluatorAgent] LLM 评估失败，返回空评估: {}", e.getMessage());
                return null;
            }
        });
    }

    /**
     * 将本轮刚被回答的问题写入临时题库，供后续人工审核。
     *
     * <p>题目为空时跳过；岗位大类为空时使用 {@code GENERAL}，环节为空时保存空字符串，
     * 题目项的 {@code id} 字段传入面试编号，并由题库工具保存为来源面试编号。
     * 题库工具会负责去重，本方法会吞掉去重、权限或持久化过程中的所有异常，不影响本轮评估。</p>
     */
    private void saveQuestionToTemporaryRag(InterviewContext context, String question) {
        try {
            if (question == null || question.isBlank()) {
                return;
            }
            QuestionBankItem item = new QuestionBankItem();
            item.setJobCategory(context.getJobCategory() == null ? "GENERAL" : context.getJobCategory());
            item.setPhase(context.getCurrentPhase() == null ? "" : context.getCurrentPhase().name());
            item.setTopicId(context.getCurrentTopicId());
            item.setTopicName(context.getCurrentTopicName());
            item.setContent(question);
            item.setExpectedAnswer(null);
            item.setId(context.getInterviewId());
            questionBankTool.saveTemporaryQuestion(item);
        } catch (Exception e) {
            log.warn("[EvaluatorAgent] 写入临时 RAG 失败，不影响评估流程: {}", e.getMessage());
        }
    }

    /**
     * 将评估结果转换为通用追问信号。
     *
     * <p>空结果默认从 L1 继续；非空结果将深度限制在 L1-L5，只有 {@code STRUGGLED} 会停止继续追问。
     * 当前协调主流程不直接调用本方法，而是使用具体 Skill 的信号提取逻辑或本地降级工具。</p>
     */
    public EvaluationSignal extractSignal(EvaluationResult result) {
        EvaluationSignal signal = new EvaluationSignal();
        if (result == null) {
            signal.setSuggestedNextDepth(1);
            signal.setContinueProbing(true);
            return signal;
        }
        signal.setSuggestedNextDepth(clampDepth(result.getSuggestedNextDepth()));
        signal.setKeyEventType(result.getKeyEvent());
        signal.setContinueProbing(!"STRUGGLED".equalsIgnoreCase(result.getKeyEvent()));
        signal.setBriefComment(result.getComment());
        return signal;
    }

    /**
     * 解析模型约定的 assessment 对象。
     *
     * <p>空响应或缺少 assessment 时返回 {@code null}。合法对象中缺失或非法的数字字段统一按 70 处理；
     * 因此 suggestedNextDepth 缺失时会先变为 70，再被限制为 L5。JSON 结构或类型解析失败时，
     * 改用只提取少数字段的正则宽松解析。</p>
     */
    private EvaluationResult parseEvaluation(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            String cleaned = json.trim();
            if (cleaned.startsWith("```")) {
                cleaned = cleaned.replaceAll("^```(json)?\\s*", "").replaceAll("\\s*```$", "").trim();
            }
            Map<?, ?> root = objectMapper.readValue(cleaned, Map.class);
            Object assessmentObj = root.get("assessment");
            if (assessmentObj == null) {
                return null;
            }
            Map<?, ?> assessment = (Map<?, ?>) assessmentObj;
            EvaluationResult result = new EvaluationResult();
            result.setOverall(toString(assessment.get("overall")));
            result.setTechnicalDepth(toInt(assessment.get("technicalDepth")));
            result.setTechnicalBreadth(toInt(assessment.get("technicalBreadth")));
            result.setPracticalExperience(toInt(assessment.get("practicalExperience")));
            result.setExpression(toInt(assessment.get("expression")));
            result.setLearningAbility(toInt(assessment.get("learningAbility")));
            result.setSuggestedNextDepth(clampDepth(toInt(assessment.get("suggestedNextDepth"))));
            result.setKeyEvent(toString(assessment.get("keyEvent")));
            result.setKeyEventReason(toString(assessment.get("keyEventReason")));
            result.setComment(toString(assessment.get("comment")));
            result.setStrengths(toStringList(assessment.get("strengths")));
            result.setWeaknesses(toStringList(assessment.get("weaknesses")));
            return result;
        } catch (Exception e) {
            log.warn("[EvaluatorAgent] 评估 JSON 解析失败: {}", e.getMessage());
            return fallbackParse(json);
        }
    }

    /**
     * 从非标准响应中尽量提取总体评价、建议深度和关键事件。
     * 即使三个字段都未匹配到也会返回空的 {@link EvaluationResult}，不会返回 {@code null}。
     */
    private EvaluationResult fallbackParse(String raw) {
        EvaluationResult result = new EvaluationResult();
        Matcher overall = Pattern.compile("\"overall\"\\s*:\\s*\"([^\"]+)\"").matcher(raw);
        if (overall.find()) result.setOverall(overall.group(1));
        Matcher depth = Pattern.compile("\"suggestedNextDepth\"\\s*:\\s*(\\d+)").matcher(raw);
        if (depth.find()) result.setSuggestedNextDepth(clampDepth(Integer.parseInt(depth.group(1))));
        Matcher keyEvent = Pattern.compile("\"keyEvent\"\\s*:\\s*\"([^\"]+)\"").matcher(raw);
        if (keyEvent.find()) result.setKeyEvent(keyEvent.group(1));
        return result;
    }

    /**
     * 将建议深度限制在 L1-L5；直接传入 {@code null} 时使用 L1。
     */
    private int clampDepth(Integer depth) {
        if (depth == null) return 1;
        return Math.max(1, Math.min(5, depth));
    }

    private String toString(Object obj) {
        return obj == null ? null : obj.toString();
    }

    /**
     * 将模型字段转为整数；字段缺失或不是合法数字时使用默认分 70。
     */
    private int toInt(Object obj) {
        if (obj == null) return 70;
        if (obj instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(obj.toString());
        } catch (NumberFormatException e) {
            return 70;
        }
    }

    @SuppressWarnings("unchecked")
    private List<String> toStringList(Object obj) {
        if (obj instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        return null;
    }
}
