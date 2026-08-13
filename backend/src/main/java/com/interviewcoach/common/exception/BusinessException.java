package com.interviewcoach.common.exception;

import lombok.Getter;

/**
 * 业务服务或基础设施适配器在规则拒绝、输入处理或业务执行失败时抛出的统一运行时异常。
 *
 * <p>{@link GlobalExceptionHandler} 将其转换为 {@code ApiResponse} 业务错误；异常中的业务码
 * 不等同于 HTTP 状态码，当前处理器也不会仅因该异常自动改变 HTTP 状态。</p>
 */
@Getter
public class BusinessException extends RuntimeException {

    /**
     * 返回给调用方并写入请求诊断上下文的业务错误码，不是 HTTP 状态码。
     */
    private final int code;

    public BusinessException(int code, String message) {
        super(message);
        this.code = code;
    }

    public BusinessException(int code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }
}
