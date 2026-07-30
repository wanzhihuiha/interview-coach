package com.interviewcoach.resume.application.service;

/**
 * 简历模块业务错误码。
 */
public final class ResumeErrorCode {

    private ResumeErrorCode() {
    }

    // 文件相关 4001-4005
    public static final int FILE_SIZE_EXCEEDED = 4001;
    public static final int FILE_TYPE_NOT_SUPPORTED = 4002;
    public static final int FILE_READ_FAILED = 4003;
    public static final int RESUME_PARSING_IN_PROGRESS = 4004;
    public static final int PARSE_CONTENT_EMPTY = 4005;

    // 业务相关 4101-4119
    public static final int RESUME_NOT_FOUND = 4101;
    public static final int RESUME_ACCESS_DENIED = 4102;
    public static final int RESUME_STATUS_INVALID = 4103;
    public static final int PROFILE_DATA_INVALID = 4104;
    public static final int RESUME_LOCKED = 4105;
    public static final int RESUME_LOCKED_FOR_DELETE = 4106;
    public static final int PROFILE_DRAFT_NOT_FOUND = 4107;
    public static final int PROFILE_DRAFT_STALE = 4108;
    public static final int PROFILE_ANALYSIS_RETRY_NOT_ALLOWED = 4109;
    public static final int USER_AI_CONCURRENCY_LIMIT = 4110;
    public static final int RESUME_AI_CONCURRENCY_LIMIT = 4111;
    public static final int AI_DAILY_SUCCESS_LIMIT = 4112;
    public static final int AI_DAILY_ATTEMPT_LIMIT = 4113;
    public static final int RETAINED_RESUME_LIMIT = 4114;
    public static final int DAILY_RESUME_CREATE_LIMIT = 4115;
    public static final int RESUME_TASK_INFRASTRUCTURE_UNAVAILABLE = 4116;
    public static final int RESUME_MUTATION_IN_PROGRESS = 4117;
    public static final int PROFILE_ANALYSIS_REQUEST_INVALID = 4118;
    public static final int PROFILE_ANALYSIS_REFINE_NOT_ALLOWED = 4119;
}
