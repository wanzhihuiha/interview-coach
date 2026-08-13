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
 * 由 Spring MVC 创建并调用的 REST 接口统一异常边界。
 *
 * <p>该处理器把 Controller 链路抛出的已知异常转换为统一 {@link ApiResponse}，同时将业务码
 * 写入当前请求诊断数据，供最外层 HTTP 完成日志消费；未预期异常只向客户端返回通用消息。</p>
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 将业务异常的业务码和安全消息写入统一响应。
     *
     * <p>没有底层 cause 的规则拒绝记 INFO；携带 cause 的处理失败记 WARN 和堆栈。
     * 此回调未设置 HTTP 状态，业务失败由响应体业务码表达。</p>
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
     * 将 {@code @Valid} 产生的字段校验错误合并为消息，并返回 HTTP 400 与业务码 400。
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
     * 将 Spring MVC 参数绑定错误合并为消息，并返回 HTTP 400 与业务码 400。
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
     * 将接口链路抛出的非法参数消息原样放入统一响应，并返回 HTTP 400 与业务码 400。
     */
    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> handleIllegalArgumentException(IllegalArgumentException e) {
        log.info("[HTTP] 请求参数非法: message={}", singleLine(e.getMessage()));
        return error(400, e.getMessage());
    }

    /**
     * 将当前实现认定的非法状态记录为带堆栈警告，并返回 HTTP 400 与业务码 400。
     */
    @ExceptionHandler(IllegalStateException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> handleIllegalStateException(IllegalStateException e) {
        log.warn("[HTTP] 请求触发非法状态: message={}", singleLine(e.getMessage()), e);
        return error(400, e.getMessage());
    }

    /**
     * 将 Spring Security 的访问拒绝统一转换为 HTTP 403，不向客户端暴露内部拒绝原因。
     */
    @ExceptionHandler(AccessDeniedException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public ApiResponse<Void> handleAccessDeniedException(AccessDeniedException e) {
        log.warn("[Security] 请求被拒绝: errorType={}", e.getClass().getSimpleName());
        return error(403, "无权访问");
    }

    /**
     * 将续期入口抛出的令牌续期拒绝转换为 HTTP 401 与业务码 401。
     */
    @ExceptionHandler(TokenRefreshException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public ApiResponse<Void> handleTokenRefreshException(TokenRefreshException e) {
        log.info("[Security] Token 续期失败: message={}", singleLine(e.getMessage()));
        return error(401, e.getMessage());
    }

    /**
     * 兜底处理其他未捕获异常，记录一次错误堆栈并返回不含内部细节的 HTTP 500 响应。
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
        // 让请求结束日志同时看到响应体业务码；后台线程没有 HTTP 上下文时该调用会安全忽略。
        HttpRequestDiagnostics.setCurrentBusinessCode(code);
        return ApiResponse.error(code, message);
    }

    /**
     * 提取校验错误涉及的字段名，按首次出现顺序去重并以逗号连接，不记录字段值。
     */
    private String fieldNames(java.util.List<FieldError> errors) {
        return errors.stream().map(FieldError::getField).distinct().collect(Collectors.joining(","));
    }

    /**
     * 沿异常 cause 链最多下探 20 层并返回截止处的类型名，避免异常链遍历失控。
     *
     * <p>20 层是当前固定诊断上限，精确取值依据缺失；更小会更早截断根因类型，
     * 更大则增加异常日志处理开销。</p>
     */
    private String rootType(Throwable error) {
        Throwable current = error;
        for (int depth = 0; current.getCause() != null && depth < 20; depth++) {
            current = current.getCause();
        }
        return current.getClass().getSimpleName();
    }

    /**
     * 将异常消息压成单行；空消息显示为 {@code -}，超过 500 个 UTF-16 代码单元时截断。
     *
     * <p>500 是当前固定日志上限，精确取值依据缺失；调小会减少诊断内容，调大则增加日志体积。</p>
     */
    private String singleLine(String message) {
        if (message == null || message.isBlank()) {
            return "-";
        }
        String value = message.replace("\r", "\\r").replace("\n", "\\n");
        return value.length() <= 500 ? value : value.substring(0, 500) + "...(truncated)";
    }
}
