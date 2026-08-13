package com.interviewcoach.resume.infrastructure.ai;

import com.interviewcoach.common.security.agent.AgentPermission;
import com.interviewcoach.common.security.agent.AgentType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * 在 {@code resume.llm.enabled=false} 或缺省时装配的固定 JSON 模型替身，供本地流程联调，不代表真实模型质量。
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "resume.llm.enabled", havingValue = "false", matchIfMissing = true)
public class MockLlmService implements LlmService {

    /**
     * 根据安全网关写入的服务端任务标识返回对应固定 JSON；未迁移任务继续使用提示词特征匹配。
     * 面试评估固定分数 70、岗位置信度 0.5 的精确依据缺失，仅用于模拟分支。
     */
    @Override
    @AgentPermission({AgentType.INTERVIEWER, AgentType.EVALUATOR, AgentType.REPORT,
            AgentType.COACH, AgentType.RESUME_ANALYSIS, AgentType.JD_ANALYSIS})
    public String chat(String systemPrompt, String userPrompt) {
        log.warn("[MockLlmService] LLM is disabled, returning mock JSON");
        // 安全任务只读取可信 systemPrompt 中的任务标识，不能让 DATA_ONLY 用户文本选择 Mock 返回结构。
        if (systemPrompt != null
                && systemPrompt.contains("[SERVER_TASK_TYPE=INTERVIEW_QUESTION_GENERATION]")) {
            return "{\"question\":\"请结合一次实际经历，说明你如何分析并解决其中的关键问题。\"}";
        }
        if (systemPrompt != null
                && systemPrompt.contains("[SERVER_TASK_TYPE=INTERVIEW_ANSWER_EVALUATION]")) {
            return """
                    {
                      "overall": "一般",
                      "technicalDepth": 70,
                      "technicalBreadth": 70,
                      "practicalExperience": 70,
                      "expression": 70,
                      "learningAbility": 70,
                      "comment": "本地模拟评估结果。"
                    }
                    """;
        }
        if (userPrompt != null
                && userPrompt.contains("\"requiredSkills\"")
                && userPrompt.contains("\"probingDirections\"")) {
            return """
                    {
                      "basicInfo": {
                        "title": "模拟岗位",
                        "company": "",
                        "location": "",
                        "level": "",
                        "salaryRange": ""
                      },
                      "requiredSkills": [
                        {
                          "skill": "岗位核心能力",
                          "importance": "必须",
                          "depth": "L2-L3"
                        }
                      ],
                      "preferredSkills": [],
                      "probingDirections": [
                        {
                          "direction": "岗位能力与项目经验",
                          "priority": 1,
                          "depthRange": "L2-L4",
                          "sampleQuestions": [
                            "请结合一个项目说明你如何解决与该岗位相关的实际问题"
                          ]
                        }
                      ],
                      "interviewFocus": [
                        "岗位理解",
                        "项目经验",
                        "问题解决能力"
                      ],
                      "confidenceLevel": 0.5
                    }
                    """;
        }
        if (userPrompt != null && userPrompt.contains("verificationPoints")) {
            return """
                    {
                      "strengths": [],
                      "verificationPoints": [],
                      "skillAssessments": []
                    }
                    """;
        }
        return """
                {
                  "basicInfo": {
                    "workingYears": "",
                    "currentPosition": "",
                    "education": ""
                  },
                  "skillTags": [],
                  "skillLevel": {},
                  "projectExperience": [],
                  "workExperience": []
                }
                """;
    }
}
