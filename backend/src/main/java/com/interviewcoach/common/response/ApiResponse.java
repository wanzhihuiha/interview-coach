package com.interviewcoach.common.response;

import lombok.Getter;
import lombok.Setter;

/**
 * Controller 和全局异常处理器跨 HTTP 边界返回的统一业务响应。
 *
 * <p>只有 {@link #code} 为 {@code 0} 表示业务成功；HTTP 状态仍由 Controller 或异常处理器
 * 单独决定，调用方不能只用 HTTP 2xx 推断业务成功。</p>
 */
@Getter
@Setter
public class ApiResponse<T> {

    /** 业务结果码；{@code 0} 为成功，非零值表示具体业务或接口错误。 */
    private int code;
    /** 面向接口调用方的结果消息，不承载 HTTP 状态本身。 */
    private String message;
    /** 成功时返回的类型化业务数据；无响应体数据或错误响应时可为 {@code null}。 */
    private T data;

    public static <T> ApiResponse<T> success(T data) {
        ApiResponse<T> response = new ApiResponse<>();
        response.setCode(0);
        response.setMessage("success");
        response.setData(data);
        return response;
    }

    public static <T> ApiResponse<T> success() {
        return success(null);
    }

    public static <T> ApiResponse<T> error(int code, String message) {
        ApiResponse<T> response = new ApiResponse<>();
        response.setCode(code);
        response.setMessage(message);
        return response;
    }
}
