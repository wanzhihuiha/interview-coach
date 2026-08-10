package com.interviewcoach.interview.domain.model;

import lombok.Data;

/**
 * 已通过任务契约校验的回答评估结果，只承载分数和简短评价，不承载面试流程决策。
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
