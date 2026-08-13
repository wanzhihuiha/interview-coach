package com.interviewcoach.interview.domain.model;

/**
 * Coordinator 根据服务端上下文和 Skill 信号选择的内部流程动作；不作为模型输出或用户枚举返回。
 */
public enum NextAction {
    /** 在当前主题、项目或场景继续生成下一题。 */
    FOLLOW_UP,
    /** 切换当前环节内的下一个主题、项目或场景。 */
    SWITCH_TOPIC,
    /** 当前环节完成，推进到用户选择的下一环节。 */
    NEXT_PHASE,
    /** 进入结束环节或完成终止流程。 */
    END_INTERVIEW
}
