package com.interviewcoach.common.security.agent;

/**
 * Agent 身份类型，用于标识调用 Tool 的 Agent。
 */
public enum AgentType {
    /**
     * 面试官 Agent：负责生成面试问题。
     */
    INTERVIEWER,

    /**
     * 评估者 Agent：负责评估候选人回答。
     */
    EVALUATOR,

    /**
     * 协调者 Agent：负责调度各环节 Skill。
     */
    COORDINATOR,

    /**
     * 报告 Agent：负责生成面试评估报告。
     */
    REPORT,

    /**
     * 教练 Agent：负责生成成长方案。
     */
    COACH,

    /**
     * 简历分析 Agent：负责解析简历文本。
     */
    RESUME_ANALYSIS,

    /**
     * JD 分析 Agent：负责解析岗位描述。
     */
    JD_ANALYSIS
}
