package com.interviewcoach.resume.infrastructure.ai;

import com.interviewcoach.common.security.agent.AgentPermission;
import com.interviewcoach.common.security.agent.AgentType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * 模拟 LLM 服务实现，用于本地开发或 LLM 未配置时的兜底。
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "resume.llm.enabled", havingValue = "false", matchIfMissing = true)
public class MockLlmService implements LlmService {

    @Override
    @AgentPermission({AgentType.INTERVIEWER, AgentType.EVALUATOR, AgentType.REPORT,
            AgentType.COACH, AgentType.RESUME_ANALYSIS, AgentType.JD_ANALYSIS})
    public String chat(String systemPrompt, String userPrompt) {
        log.warn("[MockLlmService] LLM is disabled, returning mock JSON");
        if (systemPrompt != null
                && systemPrompt.contains("[SERVER_TASK_TYPE=INTERVIEW_QUESTION_GENERATION]")) {
            return "{\"question\":\"请结合一次实际经历，说明你如何分析并解决其中的关键问题。\"}";
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
