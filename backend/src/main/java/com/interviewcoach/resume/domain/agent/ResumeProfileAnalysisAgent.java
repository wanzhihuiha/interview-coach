package com.interviewcoach.resume.domain.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.interviewcoach.common.security.agent.AgentContext;
import com.interviewcoach.common.security.agent.AgentType;
import com.interviewcoach.resume.domain.entity.ResumeProfileAnalysisMode;
import com.interviewcoach.resume.domain.model.ResumeProfileAnalysisData;
import com.interviewcoach.resume.domain.model.UserProfileData;
import com.interviewcoach.resume.infrastructure.ai.LlmService;
import com.interviewcoach.resume.infrastructure.desensitize.ResumeDesensitizer;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 由辅助分析 Worker 调用，在 RESUME_ANALYSIS Agent 权限上下文中基于用户已确认事实生成待验证选题线索。
 * 上一次成功分析和用户反馈只调整角度，不会写回事实画像，也不能作为新事实或技能等级依据；本 Agent 不负责面试评分。
 */
@Component
@RequiredArgsConstructor
public class ResumeProfileAnalysisAgent {

    /**
     * 辅助分析 Prompt 的当前版本，状态服务会随成功结果持久化。
     * 仓库未提供 resume-insights-v2 的升级或兼容记录，版本命名依据缺失；修改会改变后续结果的 Prompt 版本标记。
     */
    public static final String PROMPT_VERSION = "resume-insights-v2";

    /** 将所有输出限定为待验证假设、禁止把简历未提及内容视为能力缺失的系统提示词。 */
    private static final String SYSTEM_PROMPT = """
            你是模拟面试选题分析助手。所有判断都只是待验证假设，不得把简历未提及等同于候选人不会。
            """;

    /** 定义优势、验证点和技能判断的 JSON 字段、证据引用及敏感信息边界。 */
    private static final String OUTPUT_REQUIREMENTS = """
            要求：
            1. strengths 是有事实依据的潜在优势。
            2. verificationPoints 是面试中值得验证的能力点，不使用确定性的“薄弱”结论。
            3. skillAssessments 是根据项目和工作证据推断的技能水平，不能覆盖用户明确声明的水平。
            4. 每项尽量提供 evidenceRefs，格式可使用 project:0、work:0、skill:Java。
            5. 不输出姓名、联系方式、证件、生日或详细地址。
            6. 只输出合法 JSON。

            输出格式：
            {
              "strengths":[{"content":"...","evidenceRefs":["project:0"],"confidence":0.8}],
              "verificationPoints":[{"content":"...","evidenceRefs":[],"confidence":0.6}],
              "skillAssessments":[{"skill":"Java","inferredLevel":"熟练","evidenceRefs":["work:0"],"confidence":0.7}]
            }
            """;

    /** INITIAL 与 REGENERATE 共用的生成模板，依次接收输出约束和脱敏后的已确认事实。 */
    private static final String GENERATE_PROMPT_TEMPLATE = """
            根据下面已经由用户确认的简历事实，生成辅助面试选题的 JSON。

            %s

            已确认事实：
            %s
            """;

    /**
     * REFINE 模板，依次接收输出约束、已确认事实、上次成功分析和本次用户意见的脱敏 JSON/文本。
     */
    private static final String REFINE_PROMPT_TEMPLATE = """
            根据下面已经由用户确认的简历事实，重新生成辅助面试选题的 JSON。

            上一次成功分析和本次用户意见只用于调整分析角度与重点，不是新增事实，不能据此直接指定技能等级。

            %s

            已确认事实：
            %s

            上一次成功分析：
            %s

            本次用户意见：
            %s
            """;

    /** 执行实际或 Mock 模型调用并返回原始文本的 LLM 接口。 */
    private final LlmService llmService;
    /** 序列化模型输入并解析输出 JSON 的项目 ObjectMapper。 */
    private final ObjectMapper objectMapper;
    /** 在发送前再次屏蔽正式画像、旧分析和用户反馈中已实现敏感类型的脱敏器。 */
    private final ResumeDesensitizer desensitizer;

    /**
     * 将已确认事实再次脱敏后发送给模型，返回的判断仅作为待验证选题线索。
     */
    public ResumeProfileAnalysisData analyze(UserProfileData profileData) {
        // 兼容入口按 REGENERATE 生成，不携带旧结果、反馈或额度调用前回调。
        return analyze(
                profileData,
                null,
                null,
                ResumeProfileAnalysisMode.REGENERATE,
                () -> { });
    }

    /**
     * 根据任务模式准备模型输入；所有序列化、模式校验和脱敏完成后才执行唯一调用前回调。
     */
    public ResumeProfileAnalysisData analyze(
            UserProfileData profileData,
            ResumeProfileAnalysisData previousAnalysis,
            String feedback,
            ResumeProfileAnalysisMode mode,
            Runnable beforeModelCall) {
        // Agent 权限上下文包裹序列化、脱敏、模型调用和响应解析，结束后由通用上下文恢复。
        return AgentContext.runAs(AgentType.RESUME_ANALYSIS, () -> {
            if (profileData == null) {
                throw new IllegalArgumentException("正式画像不能为空");
            }
            if (mode == null) {
                throw new IllegalArgumentException("辅助分析模式不能为空");
            }
            if (beforeModelCall == null) {
                throw new IllegalArgumentException("模型调用前回调不能为空");
            }

            // 即使正式画像已经由用户确认，也在外部发送前序列化并再次执行本地脱敏。
            String desensitizedProfile = desensitizeJson(profileData, "正式画像无法序列化");
            String userPrompt;
            if (mode == ResumeProfileAnalysisMode.REFINE) {
                if (previousAnalysis == null) {
                    throw new IllegalArgumentException("REFINE 缺少上一次成功分析");
                }
                if (feedback == null || feedback.isBlank()) {
                    throw new IllegalArgumentException("REFINE 缺少本次用户意见");
                }
                // 旧成功分析与用户意见分别脱敏，只作为生成角度上下文，不写入事实画像。
                String desensitizedPrevious = desensitizeJson(
                        previousAnalysis, "上一次成功分析无法序列化");
                String desensitizedFeedback = desensitizer.desensitize(feedback).text();
                userPrompt = REFINE_PROMPT_TEMPLATE.formatted(
                        OUTPUT_REQUIREMENTS,
                        desensitizedProfile,
                        desensitizedPrevious,
                        desensitizedFeedback);
            } else if (mode == ResumeProfileAnalysisMode.INITIAL
                    || mode == ResumeProfileAnalysisMode.REGENERATE) {
                userPrompt = GENERATE_PROMPT_TEMPLATE.formatted(
                        OUTPUT_REQUIREMENTS, desensitizedProfile);
            } else {
                throw new IllegalArgumentException("不支持的辅助分析模式");
            }

            // 全部输入校验、序列化、模式选择与脱敏完成后才标记调用开始；回调失败不会访问 LLM。
            beforeModelCall.run();
            // 发送系统提示词和已脱敏用户提示词，远程或 Mock 失败向 Worker 传播。
            String response = llmService.chat(SYSTEM_PROMPT, userPrompt);
            // 解析并规范化数量、证据集合和置信度；坏 JSON 使任务进入失败状态。
            return parse(response);
        });
    }

    /** 将对象序列化后交给简历脱敏器；序列化失败转为不含原始数据的参数异常。 */
    private String desensitizeJson(Object value, String errorMessage) {
        try {
            return desensitizer.desensitize(objectMapper.writeValueAsString(value)).text();
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(errorMessage, e);
        }
    }

    /**
     * 从模型文本提取 JSON，过滤无内容条目、补空证据集合、夹取置信度并执行当前数量上限。
     */
    private ResumeProfileAnalysisData parse(String response) {
        if (response == null || response.isBlank()) {
            throw new IllegalArgumentException("画像分析结果为空");
        }
        String trimmed = response.trim();
        int firstBrace = trimmed.indexOf('{');
        int lastBrace = trimmed.lastIndexOf('}');
        String json = firstBrace >= 0 && lastBrace > firstBrace
                ? trimmed.substring(firstBrace, lastBrace + 1) : trimmed;
        try {
            ResumeProfileAnalysisData data = objectMapper.readValue(json, ResumeProfileAnalysisData.class);
            data.setStrengths(normalizeItems(data.getStrengths()));
            data.setVerificationPoints(normalizeItems(data.getVerificationPoints()));
            data.setSkillAssessments(normalizeSkills(data.getSkillAssessments()));
            return data;
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("画像分析 JSON 无效", e);
        }
    }

    /**
     * 过滤空优势/验证点，补齐证据和置信度，并最多保留前 8 项。
     * 上限 8 的产品、提示词或容量依据缺失；调大增加返回与存储体积，调小会截断更多模型条目。
     */
    private List<ResumeProfileAnalysisData.AnalysisItem> normalizeItems(
            List<ResumeProfileAnalysisData.AnalysisItem> items) {
        if (items == null) {
            return List.of();
        }
        return items.stream().filter(item -> item != null && item.getContent() != null
                        && !item.getContent().isBlank())
                .peek(item -> {
                    item.setEvidenceRefs(item.getEvidenceRefs() == null ? List.of() : item.getEvidenceRefs());
                    item.setConfidence(normalizeConfidence(item.getConfidence()));
                })
                .limit(8)
                .toList();
    }

    /**
     * 过滤空技能判断，补齐证据和置信度，并最多保留前 20 项。
     * 上限 20 的产品、提示词或容量依据缺失；调大增加返回与存储体积，调小会截断更多技能判断。
     */
    private List<ResumeProfileAnalysisData.SkillAssessment> normalizeSkills(
            List<ResumeProfileAnalysisData.SkillAssessment> items) {
        if (items == null) {
            return List.of();
        }
        return items.stream().filter(item -> item != null && item.getSkill() != null
                        && !item.getSkill().isBlank())
                .peek(item -> {
                    item.setEvidenceRefs(item.getEvidenceRefs() == null ? List.of() : item.getEvidenceRefs());
                    item.setConfidence(normalizeConfidence(item.getConfidence()));
                })
                .limit(20)
                .toList();
    }

    /** 将模型置信度限制到 0.0 至 1.0，缺失时使用 0.0；该值只表示模型输出强度，不是事实。 */
    private Double normalizeConfidence(Double confidence) {
        if (confidence == null) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, confidence));
    }
}
