package com.interviewcoach.common.security.agent;

/**
 * 标识调用 Tool 的服务端 Agent 身份。
 *
 * <p>枚举名作为权限注解/YAML 中的稳定英文编码，并以字符串写入审计表；
 * {@link #displayName} 由管理端审计响应转换为中文调用者 Label。</p>
 */
public enum AgentType {
    /**
     * 面试官 Agent：负责生成面试问题。
     */
    INTERVIEWER("面试官"),

    /**
     * 评估者 Agent：负责评估候选人回答。
     */
    EVALUATOR("回答评估"),

    /**
     * 协调者 Agent：负责调度各环节 Skill。
     */
    COORDINATOR("流程协调"),

    /**
     * 报告 Agent：负责生成面试评估报告。
     */
    REPORT("报告生成"),

    /**
     * 教练 Agent：负责生成成长方案。
     */
    COACH("成长教练"),

    /**
     * 简历分析 Agent：负责解析简历文本。
     */
    RESUME_ANALYSIS("简历分析"),

    /**
     * JD 分析 Agent：负责解析岗位描述。
     */
    JD_ANALYSIS("岗位分析");

    /** 与稳定英文身份编码配套、供管理端响应展示的中文名称。 */
    private final String displayName;

    AgentType(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
