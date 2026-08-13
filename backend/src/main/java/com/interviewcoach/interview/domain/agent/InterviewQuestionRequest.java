package com.interviewcoach.interview.domain.agent;

import java.util.Objects;

/**
 * 面试出题安全任务的可信服务端参数。
 *
 * <p>题型、目标深度和计数由 Skill/Interviewer 创建，跨入安全网关后用于固定任务约束；岗位、
 * 简历、上一问答等外部文本必须另放 DATA_ONLY 数据块，不能进入本记录。</p>
 *
 * @param kind 服务端选择的固定题型
 * @param targetDepth 专业题目标深度 L1～L5；其他题型固定为 0
 * @param phaseQuestionCount 当前环节已提问计数，当前由自我介绍题指令使用
 * @param itemIndex 当前项目或行为场景的零基索引；不适用题型固定为 0
 */
public record InterviewQuestionRequest(
        InterviewQuestionKind kind,
        int targetDepth,
        int phaseQuestionCount,
        int itemIndex) {

    /** 统一校验非负计数以及专业/非专业题的深度边界。 */
    public InterviewQuestionRequest {
        Objects.requireNonNull(kind, "面试题类型不能为空");
        if (phaseQuestionCount < 0 || itemIndex < 0) {
            throw new IllegalArgumentException("面试题服务端计数不能为负数");
        }
        boolean professional = kind == InterviewQuestionKind.PROFESSIONAL
                || kind == InterviewQuestionKind.PROFESSIONAL_FOLLOW_UP;
        if (professional && (targetDepth < 1 || targetDepth > 5)) {
            throw new IllegalArgumentException("专业题目标深度必须在 L1-L5 之间");
        }
        if (!professional && targetDepth != 0) {
            throw new IllegalArgumentException("非专业题不能设置目标深度");
        }
    }

    /** 创建自我介绍请求，保留当前已提问计数并把其他数值固定为 0。 */
    public static InterviewQuestionRequest selfIntro(int questionCount) {
        return new InterviewQuestionRequest(
                InterviewQuestionKind.SELF_INTRO, 0, questionCount, 0);
    }

    /** 创建指定服务端目标深度的专业主题首题请求。 */
    public static InterviewQuestionRequest professional(int targetDepth) {
        return new InterviewQuestionRequest(
                InterviewQuestionKind.PROFESSIONAL, targetDepth, 0, 0);
    }

    /** 创建指定服务端目标深度的专业追问请求；上一问答由数据块提供。 */
    public static InterviewQuestionRequest professionalFollowUp(int targetDepth) {
        return new InterviewQuestionRequest(
                InterviewQuestionKind.PROFESSIONAL_FOLLOW_UP, targetDepth, 0, 0);
    }

    /** 创建主题切换请求；下一主题文本由数据块提供，四个数值参数均为 0。 */
    public static InterviewQuestionRequest topicTransition() {
        return new InterviewQuestionRequest(
                InterviewQuestionKind.TOPIC_TRANSITION, 0, 0, 0);
    }

    /** 创建简历项目核验请求，并保存服务端选定的项目索引。 */
    public static InterviewQuestionRequest resumeDiscussion(int projectIndex) {
        return new InterviewQuestionRequest(
                InterviewQuestionKind.RESUME_DISCUSSION, 0, 0, projectIndex);
    }

    /** 创建行为题请求，并保存服务端轮转的场景索引。 */
    public static InterviewQuestionRequest behavioral(int scenarioIndex) {
        return new InterviewQuestionRequest(
                InterviewQuestionKind.BEHAVIORAL, 0, 0, scenarioIndex);
    }

    /** 创建结束语请求；无需深度、计数或项目/场景索引。 */
    public static InterviewQuestionRequest ending() {
        return new InterviewQuestionRequest(InterviewQuestionKind.ENDING, 0, 0, 0);
    }
}
