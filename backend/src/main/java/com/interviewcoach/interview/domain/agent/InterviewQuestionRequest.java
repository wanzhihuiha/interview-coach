package com.interviewcoach.interview.domain.agent;

import java.util.Objects;

/**
 * 面试出题的服务端参数，不承载岗位、简历、回答或其他外部文本。
 */
public record InterviewQuestionRequest(
        InterviewQuestionKind kind,
        int targetDepth,
        int phaseQuestionCount,
        int itemIndex) {

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

    public static InterviewQuestionRequest selfIntro(int questionCount) {
        return new InterviewQuestionRequest(
                InterviewQuestionKind.SELF_INTRO, 0, questionCount, 0);
    }

    public static InterviewQuestionRequest professional(int targetDepth) {
        return new InterviewQuestionRequest(
                InterviewQuestionKind.PROFESSIONAL, targetDepth, 0, 0);
    }

    public static InterviewQuestionRequest professionalFollowUp(int targetDepth) {
        return new InterviewQuestionRequest(
                InterviewQuestionKind.PROFESSIONAL_FOLLOW_UP, targetDepth, 0, 0);
    }

    public static InterviewQuestionRequest topicTransition() {
        return new InterviewQuestionRequest(
                InterviewQuestionKind.TOPIC_TRANSITION, 0, 0, 0);
    }

    public static InterviewQuestionRequest resumeDiscussion(int projectIndex) {
        return new InterviewQuestionRequest(
                InterviewQuestionKind.RESUME_DISCUSSION, 0, 0, projectIndex);
    }

    public static InterviewQuestionRequest behavioral(int scenarioIndex) {
        return new InterviewQuestionRequest(
                InterviewQuestionKind.BEHAVIORAL, 0, 0, scenarioIndex);
    }

    public static InterviewQuestionRequest ending() {
        return new InterviewQuestionRequest(InterviewQuestionKind.ENDING, 0, 0, 0);
    }
}
