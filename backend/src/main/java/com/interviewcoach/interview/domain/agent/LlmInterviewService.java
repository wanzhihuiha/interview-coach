package com.interviewcoach.interview.domain.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.interviewcoach.common.exception.BusinessException;
import com.interviewcoach.common.llm.LlmExecutionResult;
import com.interviewcoach.common.llm.LlmFailureType;
import com.interviewcoach.common.llm.LlmTaskDefinitionRegistry;
import com.interviewcoach.common.llm.LlmTaskInput;
import com.interviewcoach.common.llm.LlmTransportException;
import com.interviewcoach.common.llm.PromptRiskDetectorChain;
import com.interviewcoach.common.llm.SafeLlmGateway;
import com.interviewcoach.common.security.agent.AgentPermission;
import com.interviewcoach.common.security.agent.AgentType;
import com.interviewcoach.interview.application.service.InterviewErrorCode;
import com.interviewcoach.interview.infrastructure.redis.InterviewLlmRateLimiter;
import com.interviewcoach.resume.infrastructure.ai.LlmService;
import org.springframework.stereotype.Component;

/**
 * 面试模块 LLM 调用入口。
 *
 * <p>旧 {@link #chat(String, String)} 暂时服务尚未迁移的评估与报告 Agent；新出题路径只能调用
 * {@link #execute(LlmTaskInput, Class)}，并在同一入口内复用现有权限和 Redis 限流。</p>
 */
@Component
public class LlmInterviewService {

    private final LlmService llmService;
    private final InterviewLlmRateLimiter rateLimiter;
    private final SafeLlmGateway safeLlmGateway;

    public LlmInterviewService(
            LlmService llmService,
            InterviewLlmRateLimiter rateLimiter,
            LlmTaskDefinitionRegistry definitionRegistry,
            PromptRiskDetectorChain riskDetector,
            ObjectMapper objectMapper) {
        this.llmService = llmService;
        this.rateLimiter = rateLimiter;
        this.safeLlmGateway = new SafeLlmGateway(
                definitionRegistry, riskDetector, objectMapper, this::safeTransportChat);
    }

    @AgentPermission({AgentType.INTERVIEWER, AgentType.EVALUATOR, AgentType.REPORT,
            AgentType.COACH, AgentType.RESUME_ANALYSIS, AgentType.JD_ANALYSIS})
    public String chat(String systemPrompt, String userPrompt) {
        return rateLimitedChat(systemPrompt, userPrompt);
    }

    /**
     * 面试出题使用的类型化安全入口。当前只允许 INTERVIEWER，后续任务迁移时再按任务扩展权限。
     */
    @AgentPermission(AgentType.INTERVIEWER)
    public <P, O> LlmExecutionResult<O> execute(
            LlmTaskInput<P> input, Class<O> expectedResponseType) {
        return safeLlmGateway.execute(input, expectedResponseType);
    }

    /**
     * 安全网关的传输适配器：把现有限流或底层异常转换成不含原始消息的稳定失败分类。
     */
    private String safeTransportChat(String systemPrompt, String userPrompt) {
        try {
            return rateLimitedChat(systemPrompt, userPrompt);
        } catch (BusinessException e) {
            LlmFailureType failureType = e.getCode()
                    == InterviewErrorCode.LLM_RATE_LIMIT_EXCEEDED.getCode()
                    ? LlmFailureType.REQUEST_RATE_LIMITED
                    : LlmFailureType.UNEXPECTED_FAILURE;
            throw new LlmTransportException(failureType, e);
        } catch (RuntimeException e) {
            throw new LlmTransportException(LlmFailureType.UNEXPECTED_FAILURE, e);
        }
    }

    private String rateLimitedChat(String systemPrompt, String userPrompt) {
        int estimatedTokens = rateLimiter.estimateTokens(systemPrompt, userPrompt);
        if (!rateLimiter.tryAcquire(estimatedTokens)) {
            throw new BusinessException(
                    InterviewErrorCode.LLM_RATE_LIMIT_EXCEEDED.getCode(),
                    "服务繁忙，请稍后重试");
        }
        return llmService.chat(systemPrompt, userPrompt);
    }
}
