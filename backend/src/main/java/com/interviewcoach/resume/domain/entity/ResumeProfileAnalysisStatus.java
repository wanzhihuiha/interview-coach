package com.interviewcoach.resume.domain.entity;

/**
 * 最新简历辅助分析任务的状态；该状态与单行中是否保留旧成功结果是两个独立维度。
 */
public enum ResumeProfileAnalysisStatus {
    /** 新任务已登记并停用旧结果的面试资格，等待 Worker 认领。 */
    PENDING,
    /** Worker 已认领当前代次并正在准备或调用模型。 */
    RUNNING,
    /** 当前代次成功写回，保留结果与当前正式画像匹配并恢复面试可用性。 */
    SUCCEEDED,
    /** 当前任务失败或被启动恢复标记失败；旧成功结果可保留展示但不能用于面试。 */
    FAILED
}
