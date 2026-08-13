package com.interviewcoach.interview.domain.model;

import lombok.Data;

/**
 * 已通过安全回答评估任务契约的评分数据。
 *
 * <p>由 {@code InterviewAnswerEvaluationTaskDefinition} 从模型响应创建，Evaluator 交给当前
 * Skill 派生流程信号；它不由模型直接控制，也不持久化为当前报告的评分来源。</p>
 */
@Data
public class EvaluationResult {

    /** 回答的整体质量，只能是任务定义允许的五个中文等级之一。 */
    private String overall;

    /** 技术原理、实现细节和问题分析深度得分，取值范围为 0～100。 */
    private Integer technicalDepth;

    /** 相关知识覆盖面得分，取值范围为 0～100。 */
    private Integer technicalBreadth;

    /** 回答中实际项目经验和落地细节得分，取值范围为 0～100。 */
    private Integer practicalExperience;

    /** 回答的条理性和表达清晰度得分，取值范围为 0～100。 */
    private Integer expression;

    /** 学习、复盘和举一反三能力得分，取值范围为 0～100。 */
    private Integer learningAbility;

    /** 长度受限且会经过安全网关风险复检的简短评价，不参与面试阶段、主题或结束状态决策。 */
    private String comment;
}
