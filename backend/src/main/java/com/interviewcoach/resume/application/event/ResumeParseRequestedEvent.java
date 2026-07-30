package com.interviewcoach.resume.application.event;

/**
 * 简历解析请求事件，在简历状态事务提交后交给后台线程执行。
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

    public ResumeParseRequestedEvent(
            Long resumeId, Long userId, Long generation, boolean forceRefresh) {
        this(resumeId, userId, generation, forceRefresh, null, null);
    }

    public ResumeParseRequestedEvent withTaskLease(ResumeAiTaskLease lease) {
        return new ResumeParseRequestedEvent(
                resumeId, userId, generation, forceRefresh, lease, quotaReservation);
    }
}
