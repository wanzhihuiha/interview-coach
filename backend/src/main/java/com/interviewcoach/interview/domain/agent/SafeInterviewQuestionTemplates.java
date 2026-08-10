package com.interviewcoach.interview.domain.agent;

import java.util.Objects;

/**
 * 模型出题不可用时使用的固定安全文本；模板不拼接任何外部内容或模型原文。
 */
public final class SafeInterviewQuestionTemplates {

    private SafeInterviewQuestionTemplates() {
    }

    public static String forKind(InterviewQuestionKind kind) {
        Objects.requireNonNull(kind, "面试题类型不能为空");
        return switch (kind) {
            case SELF_INTRO -> "请简要介绍一下自己。";
            case PROFESSIONAL -> "请结合一个你熟悉的项目，说明你如何解决其中最有挑战的问题。";
            case PROFESSIONAL_FOLLOW_UP -> "请进一步说明你刚才提到的方案中，最关键的取舍是什么？";
            case TOPIC_TRANSITION -> "接下来我们换一个话题，请介绍一下你对这个领域核心概念的理解。";
            case RESUME_DISCUSSION -> "请选择一个你最熟悉的项目，介绍你承担的职责和解决的主要问题。";
            case BEHAVIORAL -> "请分享一次你与他人协作解决复杂问题的经历。";
            case ENDING -> "本次面试到这里，你还有什么想补充或想了解的吗？";
        };
    }
}
