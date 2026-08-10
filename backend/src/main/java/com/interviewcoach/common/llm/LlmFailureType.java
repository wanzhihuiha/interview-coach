package com.interviewcoach.common.llm;

/**
 * 统一 LLM 入口对业务返回的稳定失败分类。
 *
 * <p>分类中不携带模型原文。只有普通结构错误允许原请求在同一路由重试；只有明确的临时基础设施
 * 故障允许在批准的模型范围内切换提供方。</p>
 */
public enum LlmFailureType {
    INVALID_REQUEST(false, false),
    RISK_DETECTED(false, false),
    REQUEST_RATE_LIMITED(false, false),
    PROVIDER_RATE_LIMITED(false, true),
    PROVIDER_TIMEOUT(false, true),
    TEMPORARY_PROVIDER_FAILURE(false, true),
    PERMANENT_PROVIDER_FAILURE(false, false),
    EMPTY_RESPONSE(false, false),
    INVALID_RESPONSE_FORMAT(true, false),
    INVALID_RESPONSE_CONTENT(false, false),
    EVIDENCE_VALIDATION_FAILED(false, false),
    UNEXPECTED_FAILURE(false, false);

    private final boolean sameRouteRetryAllowed;
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
