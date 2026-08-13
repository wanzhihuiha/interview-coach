package com.interviewcoach.resume.application.service;

/**
 * 数据库对当前 AI 任务终态的确认结果，用于决定 Redis quota token 应按成功还是失败结算。
 */
public enum ResumeAiTaskOutcome {
    /** 数据库已确认当前任务成功；同日 token 可结算为成功，日期已关闭时可直接清理过期凭据。 */
    SUCCESS_CONFIRMED,
    /** 数据库已确认当前任务失败；同日释放未消耗预留，日期已关闭时直接清理凭据；已开始的尝试仍计入额度。 */
    FAILURE_CONFIRMED,
    /** 无法确认数据库终态，必须保留额度 token 和恢复凭据等待后续恢复。 */
    UNKNOWN
}
