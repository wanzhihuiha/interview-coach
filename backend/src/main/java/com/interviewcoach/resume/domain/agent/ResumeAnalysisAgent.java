package com.interviewcoach.resume.domain.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.interviewcoach.common.security.agent.AgentContext;
import com.interviewcoach.common.security.agent.AgentType;
import com.interviewcoach.resume.domain.model.UserProfileData;
import com.interviewcoach.resume.infrastructure.ai.LlmService;
import com.interviewcoach.resume.infrastructure.desensitize.ResumeDesensitizer;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 简历分析智能体，负责调用 LLM 解析简历文本并生成用户画像。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ResumeAnalysisAgent {

    private static final String SYSTEM_PROMPT = """
            你是一个专业的简历解析助手，负责从简历文本中提取关键信息并生成结构化的JSON数据。
            """;

    private static final String USER_PROMPT_TEMPLATE = """
            请从以下简历文本中提取关键信息，生成结构化的JSON数据。

            **提取要求：**
            1. 只提取简历中明确提到的信息，不要推测
            2. 对不确定的信息，标注 confidence: low
            3. 技能水平分为：精通、熟练、熟悉、了解
            4. 项目经历中需要包含：项目名称（脱敏）、技术栈、职责描述
            5. 公司名称已脱敏为"某互联网公司/某创业公司"等，需根据上下文判断公司规模
            6. education 字段必须是字符串，例如"清华大学 软件工程 硕士"，禁止返回数组或对象

            **输出格式（只输出 JSON，不要添加 ```json 代码块标记或任何额外说明文字）：**
            {
              "basicInfo": {
                "name": "候选人",
                "age": "候选人年龄或年龄段",
                "gender": "性别",
                "workingYears": "总工作年限，优先用\"X年\"格式，例如\"10年\"",
                "currentPosition": "当前职位",
                "education": "学历信息（字符串）"
              },
              "experienceLevel": "JUNIOR/MID/SENIOR，根据候选人当前目标岗位相关的最新工作经验判定，而非简单按总工作年限。例如以前做建工、后来转软件开发，则以软件开发经验定级",
              "skillTags": ["技能标签列表"],
              "skillLevel": {
                "Java": "精通/熟练/熟悉/了解",
                ...
              },
              "projectExperience": [
                {
                  "name": "项目名称（脱敏）",
                  "role": "在项目中的角色",
                  "techStack": ["使用的技术栈"],
                  "description": "项目描述和职责"
                }
              ],
              "workExperience": [
                {
                  "company": "公司规模标签",
                  "position": "职位",
                  "duration": "工作时间",
                  "highlights": ["工作亮点1", "工作亮点2"]
                }
              ],
              "strengths": ["优势1", "优势2"],
              "weaknesses": ["薄弱点1", "薄弱点2"],
              "confidenceLevel": 0.0-1.0
            }

            **简历文本：**
            %s
            """;

    private final LlmService llmService;
    private final ResumeDesensitizer desensitizer;
    private final ObjectMapper objectMapper;

    /**
     * 分析简历文本并生成用户画像。
     *
     * @param resumeText 原始简历文本
     * @return 用户画像数据
     */
    public UserProfileData analyze(String resumeText) {
        return AgentContext.runAs(AgentType.RESUME_ANALYSIS, () -> {
            if (resumeText == null || resumeText.isBlank()) {
                log.warn("[ResumeAnalysisAgent] 简历文本为空，返回空画像");
                return UserProfileData.empty();
            }

            String desensitizedText = desensitizer.desensitize(resumeText);
            String userPrompt = String.format(USER_PROMPT_TEMPLATE, desensitizedText);

            try {
                String response = llmService.chat(SYSTEM_PROMPT, userPrompt);
                return parseProfile(response);
            } catch (Exception e) {
                log.error("[ResumeAnalysisAgent] LLM 解析失败，返回空画像", e);
                return UserProfileData.empty();
            }
        });
    }

    private UserProfileData parseProfile(String rawResponse) {
        String json = extractJson(rawResponse);
        try {
            UserProfileData data = objectMapper.readValue(json, UserProfileData.class);
            if (data == null) {
                return UserProfileData.empty();
            }
            // 兜底空字段
            if (data.getSkillTags() == null) data.setSkillTags(List.of());
            if (data.getProjectExperience() == null) data.setProjectExperience(List.of());
            if (data.getWorkExperience() == null) data.setWorkExperience(List.of());
            if (data.getStrengths() == null) data.setStrengths(List.of());
            if (data.getWeaknesses() == null) data.setWeaknesses(List.of());
            if (data.getConfidenceLevel() == null) data.setConfidenceLevel(0.0);
            return data;
        } catch (JsonProcessingException e) {
            log.error("[ResumeAnalysisAgent] LLM 返回 JSON 解析失败: {}", json, e);
            return UserProfileData.empty();
        }
    }

    /**
     * 从 LLM 响应中提取 JSON 内容，支持 Markdown 代码块和普通 JSON。
     */
    private String extractJson(String rawResponse) {
        if (rawResponse == null || rawResponse.isBlank()) {
            return "{}";
        }
        String trimmed = rawResponse.trim();

        // 1. 尝试提取 ```json ... ``` 或 ``` ... ``` 代码块
        int codeBlockStart = trimmed.indexOf("```");
        if (codeBlockStart != -1) {
            int contentStart = trimmed.indexOf('\n', codeBlockStart);
            if (contentStart == -1) {
                contentStart = codeBlockStart + 3;
            } else {
                // 跳过 ```json 这一行
                contentStart = contentStart + 1;
            }
            int codeBlockEnd = trimmed.lastIndexOf("```");
            if (codeBlockEnd > codeBlockStart) {
                return trimmed.substring(contentStart, codeBlockEnd).trim();
            }
        }

        // 2. 尝试定位第一个 { 和最后一个 }
        int firstBrace = trimmed.indexOf('{');
        int lastBrace = trimmed.lastIndexOf('}');
        if (firstBrace != -1 && lastBrace != -1 && lastBrace > firstBrace) {
            return trimmed.substring(firstBrace, lastBrace + 1);
        }

        return trimmed;
    }

    /**
     * 根据画像数据推断经验水平。
     * 优先使用 LLM 返回的 experienceLevel；若未返回或非法，则按工作年限兜底。
     */
    public String inferExperienceLevel(UserProfileData data) {
        if (data == null) {
            return "JUNIOR";
        }

        // 1. 优先信任 LLM 根据最新相关工作经验判定的等级
        String llmLevel = normalizeExperienceLevel(data.getExperienceLevel());
        if (llmLevel != null) {
            log.info("[ResumeAnalysisAgent] 使用 LLM 判定的经验等级: {}", llmLevel);
            return llmLevel;
        }

        // 2. 兜底：使用 basicInfo 中明确给出的总工作年限
        String workingYears = data.getBasicInfo() != null ? data.getBasicInfo().getWorkingYears() : null;
        if (workingYears != null && !workingYears.isBlank()) {
            java.util.regex.Matcher yearMatcher = java.util.regex.Pattern.compile("(\\d+)\\s*年").matcher(workingYears);
            if (yearMatcher.find()) {
                return classifyExperienceLevel(Integer.parseInt(yearMatcher.group(1)));
            }
            try {
                return classifyExperienceLevel(Integer.parseInt(workingYears.trim()));
            } catch (NumberFormatException ignored) {
                // 不是纯数字，继续从工作经历推算
            }
        }

        if (data.getWorkExperience() == null) {
            return "JUNIOR";
        }
        int years = data.getWorkExperience().stream()
                .mapToInt(ResumeAnalysisAgent::extractYears)
                .sum();
        return classifyExperienceLevel(years);
    }

    private static String normalizeExperienceLevel(String level) {
        if (level == null || level.isBlank()) {
            return null;
        }
        String normalized = level.trim().toUpperCase();
        if (normalized.contains("SENIOR") || normalized.contains("高级") || normalized.contains("资深")) {
            return "SENIOR";
        }
        if (normalized.contains("MID") || normalized.contains("中级") || normalized.contains("中高级")) {
            return "MID";
        }
        if (normalized.contains("JUNIOR") || normalized.contains("初级") || normalized.contains("入门")) {
            return "JUNIOR";
        }
        return null;
    }

    private static String classifyExperienceLevel(int years) {
        if (years >= 7) {
            return "SENIOR";
        } else if (years >= 3) {
            return "MID";
        }
        return "JUNIOR";
    }

    private static int extractYears(UserProfileData.WorkExperience work) {
        String duration = work.getDuration();
        if (duration == null || duration.isBlank()) {
            return 0;
        }
        String normalized = duration.trim();

        // 1. 优先匹配 "X年" / "X 年" / "X年以上" 格式
        java.util.regex.Matcher yearMatcher = java.util.regex.Pattern.compile("(\\d+)\\s*年").matcher(normalized);
        if (yearMatcher.find()) {
            return Integer.parseInt(yearMatcher.group(1));
        }

        // 2. 匹配起止年份，支持 2015.03-2022.03、2015/03-2022/05、2015年6月-2022年3月、2015-2022 等
        java.util.regex.Matcher startMatcher = java.util.regex.Pattern.compile("(\\d{4})").matcher(normalized);
        if (!startMatcher.find()) {
            return 0;
        }
        int startYear = Integer.parseInt(startMatcher.group(1));

        String afterStart = normalized.substring(startMatcher.end());
        java.util.regex.Matcher endMatcher = java.util.regex.Pattern.compile("(\\d{4})").matcher(afterStart);
        boolean isCurrent = java.util.regex.Pattern.compile("至今|今|present|now|current", java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(normalized).find();

        int endYear;
        if (endMatcher.find()) {
            endYear = Integer.parseInt(endMatcher.group(1));
        } else if (isCurrent) {
            endYear = java.time.Year.now().getValue();
        } else {
            // 只有开始年份，默认按持续至今计算
            endYear = java.time.Year.now().getValue();
        }

        return Math.max(0, endYear - startYear);
    }
}
