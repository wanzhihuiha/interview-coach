package com.interviewcoach.common.exception;

/**
 * 用户令牌续期入口在拒绝续期时抛出的专用异常。
 *
 * <p>它不表示普通请求中的全部 JWT 解析或认证失败；仅由
 * {@link GlobalExceptionHandler} 的续期回调转换为 HTTP 401 响应。</p>
 */
public class TokenRefreshException extends RuntimeException {

    public TokenRefreshException(String message) {
        super(message);
    }
}
