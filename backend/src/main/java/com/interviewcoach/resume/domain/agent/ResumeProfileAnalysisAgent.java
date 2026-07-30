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
 * 基于用户已确认事实生成待验证分析，不负责事实写入或面试评分。
 */
@Component
@RequiredArgsConstructor
public class ResumeProfileAnalysisAgent {

    public static final String PROMPT_VERSION = "resume-insights-v2";

    private static final String SYSTEM_PROMPT = """
            你是模拟面试选题分析助手。所有判断都只是待验证假设，不得把简历未提及等同于候选人不会。
            """;

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

    private static final String GENERATE_PROMPT_TEMPLATE = """
            根据下面已经由用户确认的简历事实，生成辅助面试选题的 JSON。

            %s

            已确认事实：
            %s
            """;

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

    private final LlmService llmService;
    private final ObjectMapper objectMapper;
    private final ResumeDesensitizer desensitizer;

    /**
     * 将已确认事实再次脱敏后发送给模型，返回的判断仅作为待验证选题线索。
     */
    public ResumeProfileAnalysisData analyze(UserProfileData profileData) {
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

            String desensitizedProfile = desensitizeJson(profileData, "正式画像无法序列化");
            String userPrompt;
            if (mode == ResumeProfileAnalysisMode.REFINE) {
                if (previousAnalysis == null) {
                    throw new IllegalArgumentException("REFINE 缺少上一次成功分析");
                }
                if (feedback == null || feedback.isBlank()) {
                    throw new IllegalArgumentException("REFINE 缺少本次用户意见");
                }
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

            beforeModelCall.run();
            String response = llmService.chat(SYSTEM_PROMPT, userPrompt);
            return parse(response);
        });
    }

    private String desensitizeJson(Object value, String errorMessage) {
        try {
            return desensitizer.desensitize(objectMapper.writeValueAsString(value)).text();
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(errorMessage, e);
        }
    }

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

    private Double normalizeConfidence(Double confidence) {
        if (confidence == null) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, confidence));
    }
}
