package com.interviewcoach.interview.domain.model;

import lombok.Data;

/**
 * 精简评估信号，传递给面试官 Agent 做决策参考。
 */
@Data
public class EvaluationSignal {

    /** 服务端策略预留的换主题信号，当前默认为 false，模型不能直接决定。 */
    private Boolean shouldSwitchTopic = false;

    /** 服务端根据合法分数计算出的关键事件，目前只使用 EXCELLENT、STRUGGLED 或空值。 */
    private String keyEventType;

    /** 当前环节是否适合继续追问，由服务端 Skill 根据分数或固定环节规则计算。 */
    private Boolean continueProbing = true;

    /** 从合法评估结果中带出的简短评价，仅作为说明信息，不直接控制面试流程。 */
    private String briefComment;
}
