package com.interviewcoach.common.llm;

/**
 * 统一 LLM 入口对业务返回的稳定失败分类。
 *
 * <p>分类中不携带模型原文。只有普通结构错误允许原请求在同一路由重试；只有明确的临时基础设施
 * 故障允许在批准的模型范围内切换提供方。</p>
 */
public enum LlmFailureType {
    /**
     * 输入为空、任务未注册、运行时类型不匹配或服务端 Prompt 无法构造。
     */
    INVALID_REQUEST(false, false),

    /**
     * 输入或输出命中风险信号，或风险检测器异常后由网关失败关闭。
     */
    RISK_DETECTED(false, false),

    /**
     * 当前应用的请求级限流拒绝，原请求不在网关内重试，也不允许切换供应商绕过限额。
     */
    REQUEST_RATE_LIMITED(false, false),

    /**
     * 传输适配器可用来表示模型供应商限流；该分类仅标记可评估降级资格，是否存在可用备选由外部路由配置决定。
     */
    PROVIDER_RATE_LIMITED(false, true),

    /**
     * 传输适配器可用来表示供应商调用超时；该分类仅标记可评估降级资格，是否存在可用备选由外部路由配置决定。
     */
    PROVIDER_TIMEOUT(false, true),

    /**
     * 传输适配器可用来表示临时供应商故障；该分类仅标记可评估降级资格，是否存在可用备选由外部路由配置决定。
     */
    TEMPORARY_PROVIDER_FAILURE(false, true),

    /**
     * 供应商返回不可恢复的请求或服务错误，不允许自动重试或降级。
     */
    PERMANENT_PROVIDER_FAILURE(false, false),

    /**
     * 供应商没有返回可解析的非空文本。
     */
    EMPTY_RESPONSE(false, false),

    /**
     * 模型文本不满足任务定义的 JSON 或字段结构；安全网关仅对此分类使用原请求重试一次。
     */
    INVALID_RESPONSE_FORMAT(true, false),

    /**
     * 响应结构可解析，但字段内容、范围或输出数据块不满足任务契约。
     */
    INVALID_RESPONSE_CONTENT(false, false),

    /**
     * 任务定义要求的事实或证据校验未通过，结果不得进入后续业务流程。
     */
    EVIDENCE_VALIDATION_FAILED(false, false),

    /**
     * 传输、解析或网关内部发生未归类异常，且没有可安全返回的模型结果。
     */
    UNEXPECTED_FAILURE(false, false);

    /**
     * {@code true} 表示安全网关可使用完全相同的 system/user message 在当前路由重试一次；
     * {@code false} 表示网关不得执行该重试。
     */
    private final boolean sameRouteRetryAllowed;

    /**
     * {@code true} 表示上层模型路由可在已批准范围内评估切换供应商；
     * {@code false} 表示该失败不能靠切换供应商绕过。该标志不证明当前部署已经配置主备供应商。
     */
    private final boolean providerFallbackAllowed;

    LlmFailureType(boolean sameRouteRetryAllowed, boolean providerFallbackAllowed) {
        this.sameRouteRetryAllowed = sameRouteRetryAllowed;
        this.providerFallbackAllowed = providerFallbackAllowed;
    }

    public boolean isSameRouteRetryAllowed() {
        return sameRouteRetryAllowed;
    }

    public boolean isProviderFallbackAllowed() {
        return providerFallbackAllowed;
    }
}
