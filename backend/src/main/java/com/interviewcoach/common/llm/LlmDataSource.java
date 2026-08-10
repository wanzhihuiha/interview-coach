package com.interviewcoach.common.llm;

/**
 * LLM 数据块的来源类型。
 *
 * <p>来源只用于检测、路由和审计；无论来源为何，数据块都不具备指令权限。</p>
 */
public enum LlmDataSource {
    SERVER_CONTEXT,
    RESUME_TEXT,
    JOB_DESCRIPTION_TEXT,
    INTERVIEW_QUESTION,
    INTERVIEW_ANSWER,
    USER_FEEDBACK,
    VERIFIED_FACTS,
    VERIFIED_EVALUATION,
    MODEL_DERIVED_CONTENT
}
