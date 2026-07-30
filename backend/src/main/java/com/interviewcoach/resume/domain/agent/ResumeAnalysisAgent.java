package com.interviewcoach.resume.domain.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.interviewcoach.common.security.agent.AgentContext;
import com.interviewcoach.common.security.agent.AgentType;
import com.interviewcoach.resume.domain.model.UserProfileData;
import com.interviewcoach.resume.infrastructure.ai.LlmService;
import com.interviewcoach.resume.infrastructure.desensitize.ResumeDesensitizer;
import com.interviewcoach.resume.infrastructure.desensitize.ResumeDesensitizer.DesensitizationResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 从脱敏后的简历文本中提取可核对事实；主观判断由独立画像分析 Agent 负责。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ResumeAnalysisAgent {

    public static final String PROMPT_VERSION = "resume-facts-v2";

    private static final String SYSTEM_PROMPT = """
            你是简历事实提取助手。只提取原文明确出现的内容，不推断优势、薄弱点、年龄、性别或姓名。
            """;

    private static final String USER_PROMPT_TEMPLATE = """
            请将下面简历中的可核对事实转换为 JSON。

            要求：
            1. 不输出姓名、年龄、性别、联系方式、证件、生日和详细地址。
            2. 公司、学校和项目名称可以保留。
            3. 技能水平只有原文明示“精通、熟练、熟悉、了解”时才填写，否则不要推断。
            4. 不输出优势、薄弱点或总体置信度。
            5. education 必须是字符串；缺失集合输出空数组或空对象。
            6. 只输出合法 JSON，不要代码块或解释。

            输出格式：
            {
              "basicInfo": {
                "workingYears": "总工作年限",
                "currentPosition": "当前职位",
                "education": "学校、专业和学历"
              },
              "skillTags": ["技能"],
              "skillLevel": {"Java": "精通/熟练/熟悉/了解"},
              "projectExperience": [
                {"name":"项目名称","role":"角色","techStack":["技术"],"description":"项目事实"}
              ],
              "workExperience": [
                {"company":"公司","position":"职位","duration":"时间","highlights":["原文明确成果"]}
              ]
            }

            简历文本：
            %s
            """;

    private final LlmService llmService;
    private final ResumeDesensitizer desensitizer;
    private final ObjectMapper objectMapper;

    /**
     * 模型或 JSON 解析失败时向上抛出异常，由后台工作器记录真实失败状态。
     */
    public UserProfileData analyze(String resumeText) {
        return analyze(resumeText, () -> { });
    }

    /**
     * 完成本地校验和脱敏后执行一次调用前回调；回调失败时绝不进入 LLM 主备调用。
     */
    public UserProfileData analyze(String resumeText, Runnable beforeModelCall) {
        return AgentContext.runAs(AgentType.RESUME_ANALYSIS, () -> {
            if (resumeText == null || resumeText.isBlank()) {
                throw new IllegalArgumentException("简历文本为空");
            }
            if (beforeModelCall == null) {
                throw new IllegalArgumentException("模型调用前回调不能为空");
            }

            DesensitizationResult result = desensitizer.desensitize(resumeText);
            log.debug("[ResumeAnalysisAgent] 简历脱敏完成: inputLength={}, outputLength={}, maskedTypes={}",
                    resumeText.length(), result.text().length(), result.maskedTypes());
            String userPrompt = USER_PROMPT_TEMPLATE.formatted(result.text());
            beforeModelCall.run();
            String response = llmService.chat(SYSTEM_PROMPT, userPrompt);
            return parseProfile(response);
        });
    }

    private UserProfileData parseProfile(String rawResponse) {
        String json = extractJson(rawResponse);
        try {
            UserProfileData data = objectMapper.readValue(json, UserProfileData.class);
            if (data == null) {
                throw new IllegalArgumentException("模型返回空画像");
            }
            return data;
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("模型返回的事实画像 JSON 无效", e);
        }
    }

    private String extractJson(String rawResponse) {
        if (rawResponse == null || rawResponse.isBlank()) {
            throw new IllegalArgumentException("模型返回内容为空");
        }
        String trimmed = rawResponse.trim();
        int firstBrace = trimmed.indexOf('{');
        int lastBrace = trimmed.lastIndexOf('}');
        if (firstBrace >= 0 && lastBrace > firstBrace) {
            return trimmed.substring(firstBrace, lastBrace + 1);
        }
        return trimmed;
    }
}
