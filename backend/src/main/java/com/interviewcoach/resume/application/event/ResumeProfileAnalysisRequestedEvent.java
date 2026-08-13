package com.interviewcoach.resume.application.event;

/**
 * 已确认画像的异步分析请求。
 *
 * <p>feedback 只在当前进程的请求与 Worker 调用栈中存在，不能持久化、回显或写入日志。</p>
 *
 * @param resumeId 待分析简历 ID
 * @param userId 简历所属用户 ID，用于归属校验和许可、额度结算
 * @param taskGeneration 本次辅助分析任务代次，用于拒绝过期 Worker 写回
 * @param taskProfileHash 本次任务绑定的正式画像哈希，用于判断结果是否仍匹配当前画像
 * @param taskLease 监听器提交 Worker 前取得的用户级和简历级许可
 * @param quotaReservation 手动任务的额度准入上下文；免费任务为空
 * @param feedback REFINE 模式使用的敏感反馈；其他模式或无反馈时为空
 * @param requiredHandoff 是否必须成功交接给异步执行器；失败时监听器据此写回失败状态
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

    /** 创建不带租约、额度和反馈的初始分析事件。 */
    public ResumeProfileAnalysisRequestedEvent(
            Long resumeId, Long userId, Long taskGeneration, String taskProfileHash) {
        this(resumeId, userId, taskGeneration, taskProfileHash, null, null, null, false);
    }

    /**
     * 返回补入执行租约的新事件，任务哈希、额度、敏感反馈和交接要求保持不变。
     *
     * @param lease 监听器按顺序取得的用户级和简历级许可
     * @return 带租约的不可变事件副本
     */
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
