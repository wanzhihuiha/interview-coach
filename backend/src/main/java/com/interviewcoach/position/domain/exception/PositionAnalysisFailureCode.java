package com.interviewcoach.position.domain.exception;

/**
 * 岗位解析任务写入数据库的稳定失败分类，不包含 JD 或模型响应正文。
 */
public enum PositionAnalysisFailureCode {
    INPUT_INVALID,
    LLM_TIMEOUT,
    WORKER_INTERRUPTED,
    LLM_REQUEST_FAILED,
    LLM_EMPTY_RESPONSE,
    LLM_INVALID_JSON,
    LLM_INVALID_PROFILE,
    QUEUE_RESERVATION_INVALID,
    WORKER_SUBMISSION_FAILED,
    APPLICATION_RESTARTED,
    UNEXPECTED_ERROR
}
