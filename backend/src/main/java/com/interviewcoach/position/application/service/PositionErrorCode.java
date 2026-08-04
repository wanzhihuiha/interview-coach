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
    public static final int JD_CONTENT_TOO_LONG = 5003;
    public static final int POSITION_PAGE_INVALID = 5004;
    public static final int PROFILE_DATA_INVALID = 5005;

    // 岗位不存在/权限
    public static final int POSITION_NOT_FOUND = 5101;
    public static final int POSITION_ACCESS_DENIED = 5102;

    // 状态错误
    public static final int POSITION_STATUS_INVALID = 5103;
    public static final int POSITION_LIMIT_EXCEEDED = 5105;
    public static final int POSITION_WAITING_LIMIT_EXCEEDED = 5106;
    public static final int POSITION_SUBMISSION_TOO_FREQUENT = 5107;
    public static final int POSITION_ANALYSIS_IN_PROGRESS = 5108;
    public static final int POSITION_ANALYSIS_TASK_STALE = 5109;
    public static final int POSITION_ARCHIVED = 5110;
    public static final int POSITION_PUBLIC_SUBMISSION_BUSY = 5111;
    public static final int POSITION_INFRASTRUCTURE_UNAVAILABLE = 5112;
    public static final int POSITION_NOT_ARCHIVED = 5113;
    public static final int POSITION_ANALYSIS_RUNNING = 5114;
    public static final int POSITION_HAS_ACTIVE_INTERVIEW = 5115;

    // 文件相关
    public static final int FILE_READ_FAILED = 5201;
    public static final int FILE_SIZE_EXCEEDED = 5202;
    public static final int FILE_TYPE_NOT_SUPPORTED = 5203;
    public static final int FILE_CONTENT_INVALID = 5204;
    public static final int PDF_PAGE_LIMIT_EXCEEDED = 5205;
    public static final int PDF_ENCRYPTED = 5206;
    public static final int FILE_EXTRACTION_BUSY = 5207;
    public static final int FILE_EXTRACTION_TIMEOUT = 5208;
    public static final int FILE_EXTRACTION_INTERRUPTED = 5209;
}
