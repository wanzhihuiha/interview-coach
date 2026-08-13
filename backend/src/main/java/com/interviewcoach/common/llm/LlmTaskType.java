package com.interviewcoach.common.llm;

/**
 * 统一 LLM 入口支持的业务任务类型。
 *
 * <p>任务类型由服务端选择，用于绑定固定任务定义、输出契约和模型路由，不能从外部文本推导。
 * 枚举值存在不等于当前可执行；只有注册了对应 {@link LlmTaskDefinition} 的值才会通过安全网关。</p>
 */
public enum LlmTaskType {
    /**
     * 根据面试阶段、深度和 DATA_ONLY 上下文生成下一道面试问题。
     */
    INTERVIEW_QUESTION_GENERATION,

    /**
     * 根据当前问题与候选人回答生成结构化单轮评价，供面试流程保存和推进。
     */
    INTERVIEW_ANSWER_EVALUATION,

    /**
     * 标识从简历正文提取结构化事实的任务，是否可执行取决于对应定义是否注册。
     */
    RESUME_FACT_EXTRACTION,

    /**
     * 标识根据简历事实生成候选人画像分析的任务，是否可执行取决于对应定义是否注册。
     */
    RESUME_PROFILE_ANALYSIS,

    /**
     * 标识从职位描述正文提取结构化岗位事实的任务，是否可执行取决于对应定义是否注册。
     */
    JOB_DESCRIPTION_FACT_EXTRACTION,

    /**
     * 标识根据岗位事实生成岗位画像分析的任务，是否可执行取决于对应定义是否注册。
     */
    JOB_DESCRIPTION_PROFILE_ANALYSIS,

    /**
     * 标识根据已完成面试数据生成结构化报告的任务，是否可执行取决于对应定义是否注册。
     */
    INTERVIEW_REPORT_GENERATION,

    /**
     * 标识根据评估结果生成成长建议的任务，是否可执行取决于对应定义是否注册。
     */
    GROWTH_COACHING
}
