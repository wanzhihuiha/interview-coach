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
 * 由事实解析 Worker 调用，在 RESUME_ANALYSIS Agent 权限上下文中把简历正文转为可核对事实画像。
 * 输入在发送给 LLM 前经过本地校验与脱敏，输出只解析事实结构；主观判断和面试评分由其他流程负责。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ResumeAnalysisAgent {

    /**
     * 事实提取 Prompt 的当前版本标识，同时参与 Redis 解析缓存 Key 隔离。
     * 仓库未提供 resume-facts-v2 的升级或兼容记录，版本命名依据缺失；修改会使后续任务使用新的缓存命名空间。
     */
    public static final String PROMPT_VERSION = "resume-facts-v2";

    /** 限制模型只提取原文明示事实且不得推断或输出指定敏感属性的系统提示词。 */
    private static final String SYSTEM_PROMPT = """
            你是简历事实提取助手。只提取原文明确出现的内容，不推断优势、薄弱点、年龄、性别或姓名。
            """;

    /**
     * 将脱敏简历正文包装为固定 JSON 契约的用户提示词模板；唯一格式占位符接收脱敏文本。
     */
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

    /** 执行实际或 Mock 模型调用并返回原始文本的 LLM 接口。 */
    private final LlmService llmService;
    /** 在外部发送前屏蔽联系方式、证件等已实现敏感类型的简历脱敏器。 */
    private final ResumeDesensitizer desensitizer;
    /** 将模型 JSON 响应解析为事实画像对象的项目 ObjectMapper。 */
    private final ObjectMapper objectMapper;

    /**
     * 模型或 JSON 解析失败时向上抛出异常，由后台工作器记录真实失败状态。
     */
    public UserProfileData analyze(String resumeText) {
        // 兼容入口使用空回调，不承担额度标记；后台 Worker 使用带回调重载。
        return analyze(resumeText, () -> { });
    }

    /**
     * 完成本地校验和脱敏后执行一次调用前回调；回调失败时绝不进入 LLM 主备调用。
     */
    public UserProfileData analyze(String resumeText, Runnable beforeModelCall) {
        // Agent 权限上下文包裹本地处理、外部模型调用和响应解析，结束后由通用上下文恢复。
        return AgentContext.runAs(AgentType.RESUME_ANALYSIS, () -> {
            if (resumeText == null || resumeText.isBlank()) {
                throw new IllegalArgumentException("简历文本为空");
            }
            if (beforeModelCall == null) {
                throw new IllegalArgumentException("模型调用前回调不能为空");
            }

            // 原始正文仅交给本地脱敏器，日志只记录长度和类型，不记录正文或掩码值。
            DesensitizationResult result = desensitizer.desensitize(resumeText);
            log.debug("[ResumeAnalysisAgent] 简历脱敏完成: inputLength={}, outputLength={}, maskedTypes={}",
                    resumeText.length(), result.text().length(), result.maskedTypes());
            String userPrompt = USER_PROMPT_TEMPLATE.formatted(result.text());
            // 所有校验、脱敏和提示词构造成功后才标记模型调用开始；回调失败则不调用 LLM。
            beforeModelCall.run();
            // 只把系统提示词和脱敏后的用户提示词发送到 LLM，失败向 Worker 传播。
            String response = llmService.chat(SYSTEM_PROMPT, userPrompt);
            // 将模型文本收敛为 UserProfileData；空值或坏 JSON 会使任务进入真实失败状态。
            return parseProfile(response);
        });
    }

    /** 从模型文本提取 JSON 并反序列化为事实画像；null 结果和坏 JSON 均拒绝。 */
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

    /** 兼容模型在 JSON 前后附带文本：有成对外层花括号时截取其范围，否则使用去空白原文。 */
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
