package com.interviewcoach.interview.domain.agent;

import java.util.Objects;

/**
 * 模型出题、输入构造或 Skill 加载不可用时使用的固定问题模板。
 *
 * <p>Interviewer 仅按服务端题型选择这里的常量文本；模板不拼接岗位、简历、回答、模型原文
 * 或其他外部内容，因此降级结果不会把不可信文本重新作为问题输出。</p>
 */
public final class SafeInterviewQuestionTemplates {

    /** 工具类型不允许实例化。 */
    private SafeInterviewQuestionTemplates() {
    }

    /**
     * 按非空服务端题型返回对应固定问题或结束语；本方法不改变环节、主题或深度状态。
     */
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
