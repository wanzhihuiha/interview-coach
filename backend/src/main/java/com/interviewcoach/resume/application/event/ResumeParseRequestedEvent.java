package com.interviewcoach.resume.application.event;

/**
 * 简历解析请求事件，由上传或手动重试流程发布，并在事务提交后由解析监听器交给 Worker。
 *
 * @param resumeId    简历 ID
 * @param userId      简历所属用户 ID
 * @param generation   本次解析代次，用于拒绝过期任务写入
 * @param forceRefresh    是否绕过已有解析缓存
 * @param taskLease       提交 Worker 前取得的用户级和简历级许可
 * @param quotaReservation 手动任务的额度上下文；免费任务为空
 */
public record ResumeParseRequestedEvent(
        Long resumeId,
        Long userId,
        Long generation,
        boolean forceRefresh,
        ResumeAiTaskLease taskLease,
        ResumeAiQuotaReservation quotaReservation) {

    /** 创建尚未取得执行许可的兼容事件，监听器提交 Worker 前会补入租约。 */
    public ResumeParseRequestedEvent(
            Long resumeId, Long userId, Long generation, boolean forceRefresh) {
        this(resumeId, userId, generation, forceRefresh, null, null);
    }

    /**
     * 返回补入执行租约的新事件，原事件及解析代次、刷新标志和额度上下文保持不变。
     *
     * @param lease 监听器按顺序取得的用户级和简历级许可
     * @return 带租约的不可变事件副本
     */
    public ResumeParseRequestedEvent withTaskLease(ResumeAiTaskLease lease) {
        return new ResumeParseRequestedEvent(
                resumeId, userId, generation, forceRefresh, lease, quotaReservation);
    }
}
