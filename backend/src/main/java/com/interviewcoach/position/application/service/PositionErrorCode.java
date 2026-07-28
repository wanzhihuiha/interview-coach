package com.interviewcoach.position.application.service;

/**
 * 岗位模块业务错误码。
 */
public final class PositionErrorCode {

    private PositionErrorCode() {
    }

    // 参数校验
    public static final int POSITION_NAME_EMPTY = 5001;
    public static final int JD_CONTENT_EMPTY = 5002;
    public static final int PROFILE_DATA_INVALID = 5005;

    // 岗位不存在/权限
    public static final int POSITION_NOT_FOUND = 5101;
    public static final int POSITION_ACCESS_DENIED = 5102;

    // 状态错误
    public static final int POSITION_STATUS_INVALID = 5103;
    public static final int POSITION_NOT_AUDITABLE = 5103;
    public static final int POSITION_NOT_ADMIN = 5104;

    // 文件相关
    public static final int FILE_READ_FAILED = 5201;
    public static final int FILE_SIZE_EXCEEDED = 5202;
    public static final int FILE_TYPE_NOT_SUPPORTED = 5203;
}
