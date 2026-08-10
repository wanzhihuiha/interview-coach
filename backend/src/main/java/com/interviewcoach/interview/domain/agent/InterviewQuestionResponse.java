package com.interviewcoach.interview.domain.agent;

/**
 * 模型允许返回的面试题响应，只有一个经过长度校验的问题字段。
 */
public record InterviewQuestionResponse(String question) {

    public static final int MAX_QUESTION_CHARACTERS = 500;

    public InterviewQuestionResponse {
        if (question == null || question.isBlank()) {
            throw new IllegalArgumentException("面试题不能为空");
        }
        question = question.strip();
        int characterCount = question.codePointCount(0, question.length());
        if (characterCount < 1 || characterCount > MAX_QUESTION_CHARACTERS) {
            throw new IllegalArgumentException(
                    "面试题必须为 1-" + MAX_QUESTION_CHARACTERS + " 个 Unicode 字符");
        }
        if (question.contains("```")) {
            throw new IllegalArgumentException("面试题不能包含代码围栏");
        }
    }

    /**
     * 避免对象进入日志时展开模型生成的问题内容。
     */
    @Override
    public String toString() {
        return "InterviewQuestionResponse[question=<redacted>]";
    }
}
