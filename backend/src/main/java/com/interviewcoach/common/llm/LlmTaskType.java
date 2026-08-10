package com.interviewcoach.common.llm;

/**
 * 统一 LLM 入口支持的业务任务类型。
 *
 * <p>任务类型由服务端选择，用于绑定固定任务定义、输出契约和模型路由，不能从外部文本推导。</p>
 */
public enum LlmTaskType {
    INTERVIEW_QUESTION_GENERATION,
    INTERVIEW_ANSWER_EVALUATION,
    RESUME_FACT_EXTRACTION,
    RESUME_PROFILE_ANALYSIS,
    JOB_DESCRIPTION_FACT_EXTRACTION,
    JOB_DESCRIPTION_PROFILE_ANALYSIS,
    INTERVIEW_REPORT_GENERATION,
    GROWTH_COACHING
}
