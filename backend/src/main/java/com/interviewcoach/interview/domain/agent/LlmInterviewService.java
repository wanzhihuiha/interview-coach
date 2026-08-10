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
 * <p>旧 {@link #chat(String, String)} 暂时服务尚未迁移的报告 Agent；已迁移的出题和回答评估路径调用
 * {@link #execute(LlmTaskInput, Class)}，并在同一入口内复用现有权限和 Redis 限流。</p>
 */
@Component
public class LlmInterviewService {

    private final LlmService llmService;
    private final InterviewLlmRateLimiter rateLimiter;
    private final SafeLlmGateway safeLlmGateway;

    /**
     * 复用现有模型服务和限流器组装安全网关；网关最终仍通过本类的受限传输方法调用模型。
     */
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

    /**
     * 尚未迁移任务使用的旧调用入口，只提供 Agent 权限和限流，不包含 DATA_ONLY 隔离与结构化响应校验。
     */
    @AgentPermission({AgentType.INTERVIEWER, AgentType.EVALUATOR, AgentType.REPORT,
            AgentType.COACH, AgentType.RESUME_ANALYSIS, AgentType.JD_ANALYSIS})
    public String chat(String systemPrompt, String userPrompt) {
        return rateLimitedChat(systemPrompt, userPrompt);
    }

    /**
     * 面试出题和回答评估使用的类型化安全入口；任务类型和响应类型仍由任务注册器双重校验。
     */
    @AgentPermission({AgentType.INTERVIEWER, AgentType.EVALUATOR})
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

    /** 在真正调用模型前按提示词估算令牌并执行 Redis 限流，旧入口和安全入口共用该限制。 */
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
