package com.interviewcoach.interview.domain.agent;

/**
 * 安全网关解析出的单字段面试题响应。
 *
 * <p>任务定义拒绝额外 JSON 字段后构造本记录；Interviewer 只把通过这里内容校验的问题正文
 * 交回 Skill 和会话写入流程。</p>
 *
 * @param question 模型生成并经空值、Unicode 字符数和代码围栏检查的问题正文
 */
public record InterviewQuestionResponse(String question) {

    /**
     * 问题正文当前固定的 Unicode code point 上限；调大将扩大响应和存储内容，500 的精确依据缺失。
     */
    public static final int MAX_QUESTION_CHARACTERS = 500;

    /** 去除两端空白，并拒绝空题、超长题和代码围栏。 */
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
