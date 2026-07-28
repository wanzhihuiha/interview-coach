package com.interviewcoach.user.application.service;

/**
 * 用户模块错误码常量。
 */
public final class UserErrorCode {

    private UserErrorCode() {
    }

    public static final int USERNAME_ALREADY_EXISTS = 1001;
    public static final int PASSWORD_NOT_MATCH = 1002;
    public static final int USERNAME_FORMAT_INVALID = 1003;
    public static final int PASSWORD_FORMAT_INVALID = 1004;
    public static final int PHONE_FORMAT_INVALID = 1005;
    public static final int SMS_CODE_INVALID = 1006;
    public static final int SMS_SEND_TOO_FREQUENT = 1007;
    public static final int PHONE_ALREADY_EXISTS = 1008;

    public static final int USER_NOT_FOUND = 2001;
    public static final int PASSWORD_ERROR = 2002;
    public static final int ACCOUNT_DISABLED = 2003;

    public static final int OLD_PASSWORD_ERROR = 3001;

    // 同意相关
    public static final int CONSENT_TYPE_INVALID = 4001;
    public static final int CONSENT_REQUIRED = 4002;

    // 管理后台
    public static final int USER_STATUS_INVALID = 6001;
}
