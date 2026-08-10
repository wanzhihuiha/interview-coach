package com.interviewcoach.interview.domain.agent;

import com.interviewcoach.interview.domain.entity.InterviewPhase;

/**
 * 面试官允许生成的固定题目类型；类型只由服务端当前流程选择。
 */
public enum InterviewQuestionKind {
    SELF_INTRO(InterviewPhase.SELF_INTRO),
    PROFESSIONAL(InterviewPhase.PROFESSIONAL),
    PROFESSIONAL_FOLLOW_UP(InterviewPhase.PROFESSIONAL),
    TOPIC_TRANSITION(InterviewPhase.PROFESSIONAL),
    RESUME_DISCUSSION(InterviewPhase.RESUME_DISCUSSION),
    BEHAVIORAL(InterviewPhase.BEHAVIORAL),
    ENDING(InterviewPhase.ENDING);

    private final InterviewPhase skillPhase;

    InterviewQuestionKind(InterviewPhase skillPhase) {
        this.skillPhase = skillPhase;
    }

    public InterviewPhase getSkillPhase() {
        return skillPhase;
    }
}
