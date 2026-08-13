package com.interviewcoach.interview.application.service;

/**
 * 题库管理模块错误码。
 */
public enum QuestionBankErrorCode {

    /** 管理端指定的临时题目不存在。 */
    QUESTION_NOT_FOUND(6201),

    /** 临时题目已不处于允许审核或编辑的待审核状态。 */
    QUESTION_STATUS_INVALID(6202),

    /** 永久题库已经存在正文完全相同的题目。 */
    QUESTION_DUPLICATE(6203),

    /** 管理端提交的题目正文为空白。 */
    QUESTION_CONTENT_EMPTY(6204);

    /** 对外业务错误码数值。 */
    private final int code;

    /** 保存枚举项对应的既有业务错误码。 */
    QuestionBankErrorCode(int code) {
        this.code = code;
    }

    /** 返回异常响应使用的业务错误码。 */
    public int getCode() {
        return code;
    }
}
