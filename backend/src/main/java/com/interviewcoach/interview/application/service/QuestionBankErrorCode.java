package com.interviewcoach.interview.application.service;

/**
 * 题库管理模块错误码。
 */
public enum QuestionBankErrorCode {

    QUESTION_NOT_FOUND(6201),
    QUESTION_STATUS_INVALID(6202),
    QUESTION_DUPLICATE(6203),
    QUESTION_CONTENT_EMPTY(6204);

    private final int code;

    QuestionBankErrorCode(int code) {
        this.code = code;
    }

    public int getCode() {
        return code;
    }
}
