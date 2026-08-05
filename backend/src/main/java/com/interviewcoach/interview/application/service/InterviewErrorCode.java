package com.interviewcoach.interview.application.service;

/**
 * 面试模块错误码。
 */
public enum InterviewErrorCode {

    RESUME_NOT_FOUND(6001),
    RESUME_STATUS_INVALID(6001),
    POSITION_NOT_FOUND(6002),
    POSITION_STATUS_INVALID(6002),
    RESUME_LOCKED(6003),
    POSITION_LOCKED(6004),
    NO_PHASE_SELECTED(6006),
    INVALID_PHASE(6107),
    INTERVIEW_NOT_FOUND(6101),
    INTERVIEW_ENDED(6102),
    INTERVIEW_INTERRUPTED(6103),
    ANSWER_INVALID(6105),
    NO_ACCESS(6104),
    INTERVIEW_INITIALIZATION_FAILED(6108),
    INTERVIEW_SNAPSHOT_INVALID(6109),
    INTERVIEW_STATE_CONFLICT(6110),
    INTERVIEW_SERVER_BUSY(6111),
    LLM_RATE_LIMIT_EXCEEDED(6112);

    private final int code;

    InterviewErrorCode(int code) {
        this.code = code;
    }

    public int getCode() {
        return code;
    }
}
