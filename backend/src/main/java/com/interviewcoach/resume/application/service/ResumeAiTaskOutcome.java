package com.interviewcoach.resume.application.service;

/**
 * 数据库对当前 AI 任务终态的确认结果，用于决定 Redis quota token 应按成功还是失败结算。
 */
public enum ResumeAiTaskOutcome {
    SUCCESS_CONFIRMED,
    FAILURE_CONFIRMED,
    UNKNOWN
}
