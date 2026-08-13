package com.interviewcoach.interview.domain.agent;

import com.interviewcoach.interview.domain.entity.InterviewPhase;

/**
 * 面试官允许生成的固定题目类型；类型只由服务端当前流程选择。
 */
public enum InterviewQuestionKind {
    /** 自我介绍环节的首题。 */
    SELF_INTRO(InterviewPhase.SELF_INTRO),

    /** 专业环节某个主题的第一题。 */
    PROFESSIONAL(InterviewPhase.PROFESSIONAL),

    /** 专业环节基于上一问答生成的追问题。 */
    PROFESSIONAL_FOLLOW_UP(InterviewPhase.PROFESSIONAL),

    /** 专业环节切换到下一主题时的过渡题。 */
    TOPIC_TRANSITION(InterviewPhase.PROFESSIONAL),

    /** 简历探讨环节围绕服务端选定项目生成的核验题。 */
    RESUME_DISCUSSION(InterviewPhase.RESUME_DISCUSSION),

    /** 行为环节按服务端场景索引生成的 STAR 风格题。 */
    BEHAVIORAL(InterviewPhase.BEHAVIORAL),

    /** 结束环节的固定类型，用于生成不含评分结论的结束语。 */
    ENDING(InterviewPhase.ENDING);

    /** 该题型读取哪一份服务端 Skill Markdown；仅供服务端选择，不暴露给模型决定。 */
    private final InterviewPhase skillPhase;

    /** 绑定题型和服务端 Skill 环节。 */
    InterviewQuestionKind(InterviewPhase skillPhase) {
        this.skillPhase = skillPhase;
    }

    /** 返回题型对应的 Skill 环节，供任务定义和加载校验使用。 */
    public InterviewPhase getSkillPhase() {
        return skillPhase;
    }
}
