package com.interviewcoach.common.exception;

/**
 * Token 续期失败异常。
 */
public class TokenRefreshException extends RuntimeException {

    public TokenRefreshException(String message) {
        super(message);
    }
}
