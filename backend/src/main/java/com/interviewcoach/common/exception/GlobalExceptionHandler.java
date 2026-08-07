package com.interviewcoach.common.exception;

import com.interviewcoach.common.observability.HttpRequestDiagnostics;
import com.interviewcoach.common.response.ApiResponse;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理器。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 业务异常。没有底层 cause 的规则拒绝记 INFO；携带 cause 的处理失败记 WARN 和堆栈。
     */
    @ExceptionHandler(BusinessException.class)
    public ApiResponse<Void> handleBusinessException(BusinessException e) {
        if (e.getCause() == null) {
            log.info("[Business] 请求被业务规则拒绝: code={}, message={}",
                    e.getCode(), singleLine(e.getMessage()));
        } else {
            log.warn("[Business] 业务处理失败: code={}, message={}, rootType={}",
                    e.getCode(), singleLine(e.getMessage()), rootType(e), e);
        }
        return error(e.getCode(), e.getMessage());
    }

    /**
     * 参数校验异常（@Valid）。
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> handleValidationException(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining("; "));
        log.info("[HTTP] 请求参数校验失败: fields={}, message={}",
                fieldNames(e.getBindingResult().getFieldErrors().stream().toList()),
                singleLine(message));
        return error(400, message);
    }

    /**
     * 参数绑定异常。
     */
    @ExceptionHandler(BindException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> handleBindException(BindException e) {
        String message = e.getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining("; "));
        log.info("[HTTP] 请求参数绑定失败: fields={}, message={}",
                fieldNames(e.getFieldErrors()), singleLine(message));
        return error(400, message);
    }

    /**
     * 非法参数异常。
     */
    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> handleIllegalArgumentException(IllegalArgumentException e) {
        log.info("[HTTP] 请求参数非法: message={}", singleLine(e.getMessage()));
        return error(400, e.getMessage());
    }

    /**
     * 非法状态异常。
     */
    @ExceptionHandler(IllegalStateException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> handleIllegalStateException(IllegalStateException e) {
        log.warn("[HTTP] 请求触发非法状态: message={}", singleLine(e.getMessage()), e);
        return error(400, e.getMessage());
    }

    /**
     * 访问拒绝异常。
     */
    @ExceptionHandler(AccessDeniedException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public ApiResponse<Void> handleAccessDeniedException(AccessDeniedException e) {
        log.warn("[Security] 请求被拒绝: errorType={}", e.getClass().getSimpleName());
        return error(403, "无权访问");
    }

    /**
     * Token 续期异常。
     */
    @ExceptionHandler(TokenRefreshException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public ApiResponse<Void> handleTokenRefreshException(TokenRefreshException e) {
        log.info("[Security] Token 续期失败: message={}", singleLine(e.getMessage()));
        return error(401, e.getMessage());
    }

    /**
     * 其他未处理异常。
     */
    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ApiResponse<Void> handleException(Exception e) {
        log.error("[HTTP] 未处理异常: errorType={}, rootType={}",
                e.getClass().getSimpleName(), rootType(e), e);
        return error(500, "系统繁忙，请稍后再试");
    }

    /**
     * 构造错误响应的同时把业务码写回请求诊断上下文，供最外层 HTTP 结束日志判定结果。
     */
    private ApiResponse<Void> error(int code, String message) {
        HttpRequestDiagnostics.setCurrentBusinessCode(code);
        return ApiResponse.error(code, message);
    }

    private String fieldNames(java.util.List<FieldError> errors) {
        return errors.stream().map(FieldError::getField).distinct().collect(Collectors.joining(","));
    }

    private String rootType(Throwable error) {
        Throwable current = error;
        for (int depth = 0; current.getCause() != null && depth < 20; depth++) {
            current = current.getCause();
        }
        return current.getClass().getSimpleName();
    }

    private String singleLine(String message) {
        if (message == null || message.isBlank()) {
            return "-";
        }
        String value = message.replace("\r", "\\r").replace("\n", "\\n");
        return value.length() <= 500 ? value : value.substring(0, 500) + "...(truncated)";
    }
}
