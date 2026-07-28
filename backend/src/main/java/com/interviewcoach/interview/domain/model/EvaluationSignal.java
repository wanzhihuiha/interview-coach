package com.interviewcoach.interview.domain.model;

import lombok.Data;

/**
 * 精简评估信号，传递给面试官 Agent 做决策参考。
 */
@Data
public class EvaluationSignal {

    private Integer suggestedNextDepth = 1;
    private Boolean shouldSwitchTopic = false;
    private String keyEventType;
    private Boolean continueProbing = true;
    private String briefComment;
}
