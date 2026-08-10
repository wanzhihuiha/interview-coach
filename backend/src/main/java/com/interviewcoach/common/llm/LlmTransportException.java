package com.interviewcoach.common.llm;

import java.util.Objects;

/**
 * 底层传输适配器向安全网关报告的稳定失败，不向业务层暴露供应商异常消息。
 */
public class LlmTransportException extends RuntimeException {

    private final LlmFailureType failureType;

    public LlmTransportException(LlmFailureType failureType, Throwable cause) {
        super(null, cause, false, false);
        this.failureType = Objects.requireNonNull(failureType, "LLM 传输失败类型不能为空");
    }

    public LlmFailureType getFailureType() {
        return failureType;
    }
}
