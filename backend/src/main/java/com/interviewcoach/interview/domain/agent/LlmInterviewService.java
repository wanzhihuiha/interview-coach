package com.interviewcoach.interview.domain.agent;

import com.interviewcoach.common.exception.BusinessException;
import com.interviewcoach.common.security.agent.AgentPermission;
import com.interviewcoach.common.security.agent.AgentType;
import com.interviewcoach.interview.application.service.InterviewErrorCode;
import com.interviewcoach.interview.infrastructure.redis.InterviewLlmRateLimiter;
import com.interviewcoach.resume.infrastructure.ai.LlmService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 面试模块 LLM 调用入口，封装现有 LlmService。
 */
@Component
@RequiredArgsConstructor
public class LlmInterviewService {

    private final LlmService llmService;
    private final InterviewLlmRateLimiter rateLimiter;

    @AgentPermission({AgentType.INTERVIEWER, AgentType.EVALUATOR, AgentType.REPORT,
            AgentType.COACH, AgentType.RESUME_ANALYSIS, AgentType.JD_ANALYSIS})
    public String chat(String systemPrompt, String userPrompt) {
        int estimatedTokens = rateLimiter.estimateTokens(systemPrompt, userPrompt);
        if (!rateLimiter.tryAcquire(estimatedTokens)) {
            throw new BusinessException(
                    InterviewErrorCode.LLM_RATE_LIMIT_EXCEEDED.getCode(),
                    "服务繁忙，请稍后重试");
        }
        return llmService.chat(systemPrompt, userPrompt);
    }
}
