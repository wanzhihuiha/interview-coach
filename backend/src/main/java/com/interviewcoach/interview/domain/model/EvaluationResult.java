package com.interviewcoach.interview.domain.model;

import java.util.List;
import lombok.Data;

/**
 * 评估结果。
 */
@Data
public class EvaluationResult {

    private String overall;
    private Integer technicalDepth;
    private Integer technicalBreadth;
    private Integer practicalExperience;
    private Integer expression;
    private Integer learningAbility;
    private Integer suggestedNextDepth;
    private String keyEvent;
    private String keyEventReason;
    private List<String> strengths;
    private List<String> weaknesses;
    private String comment;
}
