package com.interviewcoach.position.application.service;

/**
 * 岗位模块通过统一业务异常返回的稳定数值错误码。
 * 分段只反映当前代码组织，不在此推断具体数值的历史依据，也不以注释改变调用方契约。
 */
public final class PositionErrorCode {

    private PositionErrorCode() {
    }

    // 参数校验
    /** 岗位名称为空或规范化后没有有效内容。 */
    public static final int POSITION_NAME_EMPTY = 5001;
    /** JD 正文为空或规范化后没有有效内容。 */
    public static final int JD_CONTENT_EMPTY = 5002;
    /** JD 正文的 Unicode code point 数超过当前配置上限。 */
    public static final int JD_CONTENT_TOO_LONG = 5003;
    /** 页码为负、每页数量小于 1 或超过当前固定上限。 */
    public static final int POSITION_PAGE_INVALID = 5004;
    /** 岗位画像或岗位类别等结构化数据缺失、无法序列化或无法解析。 */
    public static final int PROFILE_DATA_INVALID = 5005;

    // 岗位不存在/权限
    /** 岗位不存在，或受资源归属条件保护的查询未返回目标岗位。 */
    public static final int POSITION_NOT_FOUND = 5101;
    /** 当前可信用户无权执行岗位动作，或用户记录不存在、不可用。 */
    public static final int POSITION_ACCESS_DENIED = 5102;

    // 状态错误
    /** 岗位或当前任务状态不允许执行所请求的动作。 */
    public static final int POSITION_STATUS_INVALID = 5103;
    /** 用户未归档个人岗位数已达到配置上限。 */
    public static final int POSITION_LIMIT_EXCEEDED = 5105;
    /** 指定公共或个人队列归属的 MySQL WAITING 任务数已达到配置上限。 */
    public static final int POSITION_WAITING_LIMIT_EXCEEDED = 5106;
    /** 个人用户距离上次岗位解析提交尚未达到配置间隔。 */
    public static final int POSITION_SUBMISSION_TOO_FREQUENT = 5107;
    /** 当前任务仍处于 WAITING 或 RUNNING，不能再创建替代任务。 */
    public static final int POSITION_ANALYSIS_IN_PROGRESS = 5108;
    /** 确认请求中的 taskId 已被替换，或对应任务不再是带候选画像的 SUCCEEDED 状态。 */
    public static final int POSITION_ANALYSIS_TASK_STALE = 5109;
    /** 岗位已归档，不能继续确认候选或重新解析。 */
    public static final int POSITION_ARCHIVED = 5110;
    /** 公共岗位提交锁当前被其他请求持有，本次请求未进入数据库写入。 */
    public static final int POSITION_PUBLIC_SUBMISSION_BUSY = 5111;
    /** 公共提交所需的 Redis 锁基础设施不可用，按失败关闭策略拒绝写入。 */
    public static final int POSITION_INFRASTRUCTURE_UNAVAILABLE = 5112;
    /** 永久删除请求指向尚未归档的岗位。 */
    public static final int POSITION_NOT_ARCHIVED = 5113;
    /** 岗位仍有 RUNNING 解析任务，永久删除必须等待 Worker 收口。 */
    public static final int POSITION_ANALYSIS_RUNNING = 5114;
    /** 岗位仍被进行中面试引用，永久删除被状态守卫拒绝。 */
    public static final int POSITION_HAS_ACTIVE_INTERVIEW = 5115;

    // 文件相关
    /** 上传文件为空，或临时文件创建、复制、读取等文件操作失败。 */
    public static final int FILE_READ_FAILED = 5201;
    /** 声明大小或复制时统计的实际字节数超过上传配置上限。 */
    public static final int FILE_SIZE_EXCEEDED = 5202;
    /** 调用方声明的文件类型不是当前支持的 PDF 或 TXT。 */
    public static final int FILE_TYPE_NOT_SUPPORTED = 5203;
    /** 文件签名、声明类型、UTF-8 内容或二进制控制字符校验未通过。 */
    public static final int FILE_CONTENT_INVALID = 5204;
    /** PDF 实际页数超过当前上传配置允许的页数。 */
    public static final int PDF_PAGE_LIMIT_EXCEEDED = 5205;
    /** PDF 需要密码或被识别为加密文档，当前提取流程拒绝处理。 */
    public static final int PDF_ENCRYPTED = 5206;
    /** 文件提取有界执行器已满并拒绝接收新的 Worker。 */
    public static final int FILE_EXTRACTION_BUSY = 5207;
    /** HTTP 请求等待文件提取超过配置时长；已接管文件的 Worker 仍负责真实结束与清理。 */
    public static final int FILE_EXTRACTION_TIMEOUT = 5208;
    /** HTTP 请求等待文件提取时被中断，当前线程已恢复中断标记。 */
    public static final int FILE_EXTRACTION_INTERRUPTED = 5209;
}
