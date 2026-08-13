package com.interviewcoach.common.llm;

import java.util.Objects;

/**
 * 底层模型传输适配器创建、安全网关捕获的稳定失败载体。
 *
 * <p>构造时保留底层 cause 供进程内诊断，但异常消息被置空；网关只把
 * {@link #failureType} 转换为业务可消费的失败结果，不向业务层暴露供应商原始消息。</p>
 */
public class LlmTransportException extends RuntimeException {

    /**
     * 由传输适配器确定、供安全网关决定重试或降级资格的稳定失败分类。
     */
    private final LlmFailureType failureType;

    public LlmTransportException(LlmFailureType failureType, Throwable cause) {
        super(null, cause, false, false);
        this.failureType = Objects.requireNonNull(failureType, "LLM 传输失败类型不能为空");
    }

    public LlmFailureType getFailureType() {
        return failureType;
    }
}
