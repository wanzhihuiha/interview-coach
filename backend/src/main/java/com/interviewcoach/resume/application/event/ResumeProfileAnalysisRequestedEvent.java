package com.interviewcoach.resume.application.event;

/**
 * 已确认画像的异步分析请求。
 *
 * <p>feedback 只在当前进程的请求与 Worker 调用栈中存在，不能持久化、回显或写入日志。</p>
 */
public record ResumeProfileAnalysisRequestedEvent(
        Long resumeId,
        Long userId,
        Long taskGeneration,
        String taskProfileHash,
        ResumeAiTaskLease taskLease,
        ResumeAiQuotaReservation quotaReservation,
        String feedback,
        boolean requiredHandoff) {

    public ResumeProfileAnalysisRequestedEvent(
            Long resumeId, Long userId, Long taskGeneration, String taskProfileHash) {
        this(resumeId, userId, taskGeneration, taskProfileHash, null, null, null, false);
    }

    public ResumeProfileAnalysisRequestedEvent withTaskLease(ResumeAiTaskLease lease) {
        return new ResumeProfileAnalysisRequestedEvent(
                resumeId,
                userId,
                taskGeneration,
                taskProfileHash,
                lease,
                quotaReservation,
                feedback,
                requiredHandoff);
    }

    /**
     * 防止事件被框架或诊断代码直接输出时泄漏只允许存在于内存中的 feedback。
     */
    @Override
    public String toString() {
        return "ResumeProfileAnalysisRequestedEvent[resumeId=" + resumeId
                + ", userId=" + userId
                + ", taskGeneration=" + taskGeneration
                + ", requiredHandoff=" + requiredHandoff + "]";
    }
}
