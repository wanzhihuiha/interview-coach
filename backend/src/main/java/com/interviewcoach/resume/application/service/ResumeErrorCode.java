package com.interviewcoach.resume.application.service;

/**
 * 简历模块业务错误码。
 */
public final class ResumeErrorCode {

    private ResumeErrorCode() {
    }

    /** 上传文件超过当前允许大小。 */
    public static final int FILE_SIZE_EXCEEDED = 4001;
    /** 上传文件扩展名不在当前支持范围内。 */
    public static final int FILE_TYPE_NOT_SUPPORTED = 4002;
    /** 已落盘文件读取或文本提取失败。 */
    public static final int FILE_READ_FAILED = 4003;
    /** 当前简历已有解析任务进行中，不能重复提交。 */
    public static final int RESUME_PARSING_IN_PROGRESS = 4004;
    /** 文件提取后没有可供模型解析的文本。 */
    public static final int PARSE_CONTENT_EMPTY = 4005;

    /** 指定简历不存在。 */
    public static final int RESUME_NOT_FOUND = 4101;
    /** 当前用户不是指定简历的所有者。 */
    public static final int RESUME_ACCESS_DENIED = 4102;
    /** 当前简历状态不允许所请求的操作。 */
    public static final int RESUME_STATUS_INVALID = 4103;
    /** 事实画像缺少必填信息或不满足业务校验。 */
    public static final int PROFILE_DATA_INVALID = 4104;
    /** 简历已被面试流程锁定，不能修改事实画像。 */
    public static final int RESUME_LOCKED = 4105;
    /** 简历已被面试流程锁定，不能删除。 */
    public static final int RESUME_LOCKED_FOR_DELETE = 4106;
    /** 当前解析代次没有可确认或可编辑的事实草稿。 */
    public static final int PROFILE_DRAFT_NOT_FOUND = 4107;
    /** 客户端提交的解析代次已落后于服务端当前草稿。 */
    public static final int PROFILE_DRAFT_STALE = 4108;
    /** 当前辅助分析状态不允许重新生成或继续调整。 */
    public static final int PROFILE_ANALYSIS_RETRY_NOT_ALLOWED = 4109;
    /** 当前用户持有的 AI 任务许可已达到配置上限。 */
    public static final int USER_AI_CONCURRENCY_LIMIT = 4110;
    /** 当前简历已有达到配置上限的 AI 任务。 */
    public static final int RESUME_AI_CONCURRENCY_LIMIT = 4111;
    /** 当前额度日期内成功的手动 AI 任务已达到配置上限。 */
    public static final int AI_DAILY_SUCCESS_LIMIT = 4112;
    /** 当前额度日期内手动 AI 尝试次数已达到配置上限。 */
    public static final int AI_DAILY_ATTEMPT_LIMIT = 4113;
    /** 用户保留的简历数量已达到配置上限。 */
    public static final int RETAINED_RESUME_LIMIT = 4114;
    /** 当前额度日期内创建简历的次数已达到配置上限。 */
    public static final int DAILY_RESUME_CREATE_LIMIT = 4115;
    /** Redis 许可、额度或锁等任务基础设施当前不可用。 */
    public static final int RESUME_TASK_INFRASTRUCTURE_UNAVAILABLE = 4116;
    /** 同一用户已有互斥的简历写操作进行中。 */
    public static final int RESUME_MUTATION_IN_PROGRESS = 4117;
    /** 辅助分析模式、反馈或请求上下文不合法。 */
    public static final int PROFILE_ANALYSIS_REQUEST_INVALID = 4118;
    /** 当前没有可供 REFINE 模式继续调整的旧成功结果。 */
    public static final int PROFILE_ANALYSIS_REFINE_NOT_ALLOWED = 4119;
}
