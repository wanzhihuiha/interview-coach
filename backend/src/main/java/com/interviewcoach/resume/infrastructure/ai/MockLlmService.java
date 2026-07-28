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
        log.warn("[MockLlmService] LLM is disabled, returning empty profile JSON");
        return """
                {
                  "basicInfo": {
                    "name": "候选人",
                    "workingYears": "",
                    "currentPosition": "",
                    "education": ""
                  },
                  "skillTags": [],
                  "skillLevel": {},
                  "projectExperience": [],
                  "workExperience": [],
                  "strengths": [],
                  "weaknesses": [],
                  "confidenceLevel": 0.0
                }
                """;
    }
}
