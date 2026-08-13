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
 * 面试模块共用的受限 LLM 调用入口。
 *
 * <p>旧 {@link #chat(String, String)} 暂时服务尚未迁移的调用方；已迁移的出题和回答评估路径
 * 调用 {@link #execute(LlmTaskInput, Class)}，经过任务注册、DATA_ONLY 隔离、输入/输出风险检测
 * 与严格解析。两条路径最终都复用 Agent 权限和 Redis 限流后调用现有模型服务。</p>
 */
@Component
public class LlmInterviewService {

    /** 最终发送 system/user prompt 的既有模型服务；其厂商路由不由本类决定。 */
    private final LlmService llmService;
    /** 在传输前按 60 秒窗口限制请求数和估算 Token 数。 */
    private final InterviewLlmRateLimiter rateLimiter;
    /** 负责类型注册、数据隔离、风险检测、严格解析和稳定失败分类的安全网关。 */
    private final SafeLlmGateway safeLlmGateway;

    /**
     * 复用任务注册表、风险检测链和 JSON 工具组装安全网关；网关最终仍通过本类的受限传输
     * 方法执行 Redis 限流并调用现有模型服务。
     */
    public LlmInterviewService(
            LlmService llmService,
            InterviewLlmRateLimiter rateLimiter,
            LlmTaskDefinitionRegistry definitionRegistry,
            PromptRiskDetectorChain riskDetector,
            ObjectMapper objectMapper) {
        this.llmService = llmService;
        this.rateLimiter = rateLimiter;
        // 传输回调固定进入 safeTransportChat，使安全任务无法绕过面试模块限流和异常分类。
        this.safeLlmGateway = new SafeLlmGateway(
                definitionRegistry, riskDetector, objectMapper, this::safeTransportChat);
    }

    /**
     * 尚未迁移任务使用的旧调用入口，只提供 Agent 权限和限流，不包含 DATA_ONLY 隔离与结构化
     * 响应校验。当前报告 Agent 直接依赖底层 {@code LlmService}，并未调用本旧入口。
     */
    @AgentPermission({AgentType.INTERVIEWER, AgentType.EVALUATOR, AgentType.REPORT,
            AgentType.COACH, AgentType.RESUME_ANALYSIS, AgentType.JD_ANALYSIS})
    public String chat(String systemPrompt, String userPrompt) {
        // 权限切面校验当前 Agent 类型后，两段原始 prompt 直接进入共用限流传输。
        return rateLimitedChat(systemPrompt, userPrompt);
    }

    /**
     * 面试出题和回答评估使用的类型化安全入口；权限切面只允许 INTERVIEWER/EVALUATOR，任务
     * 类型、参数类型和响应类型由安全网关及任务注册器校验，失败返回稳定分类对象。
     */
    @AgentPermission({AgentType.INTERVIEWER, AgentType.EVALUATOR})
    public <P, O> LlmExecutionResult<O> execute(
            LlmTaskInput<P> input, Class<O> expectedResponseType) {
        // 网关完成输入风险检测、结构化 prompt、受限传输、严格响应解析和输出复检。
        return safeLlmGateway.execute(input, expectedResponseType);
    }

    /**
     * 安全网关的传输适配器：把现有限流或底层异常转换成不含原始消息的稳定失败分类。
     * 限流错误映射为 REQUEST_RATE_LIMITED，其他业务或运行时异常映射为 UNEXPECTED_FAILURE。
     */
    private String safeTransportChat(String systemPrompt, String userPrompt) {
        try {
            // 传输前先扣减 Redis 限额；只有许可成功才调用底层模型服务。
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

    /**
     * 在真正调用模型前估算两段 prompt 的 Token 数并执行 Redis 限流。
     * 许可失败抛出稳定业务错误；许可成功后底层模型异常原样向旧入口传播或由安全适配器分类。
     */
    private String rateLimitedChat(String systemPrompt, String userPrompt) {
        // 本地估算结果只用于限流扣减，不作为模型实际用量或计费凭证。
        int estimatedTokens = rateLimiter.estimateTokens(systemPrompt, userPrompt);
        if (!rateLimiter.tryAcquire(estimatedTokens)) {
            throw new BusinessException(
                    InterviewErrorCode.LLM_RATE_LIMIT_EXCEEDED.getCode(),
                    "服务繁忙，请稍后重试");
        }
        return llmService.chat(systemPrompt, userPrompt);
    }
}
