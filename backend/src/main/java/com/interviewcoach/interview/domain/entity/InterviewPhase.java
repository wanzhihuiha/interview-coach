package com.interviewcoach.interview.domain.entity;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * 面试环节。固定顺序：自我介绍 -> 专业面试 -> 简历探讨 -> 行为面试 -> 结束。
 */
public enum InterviewPhase {
    SELF_INTRO("自我介绍", 1),
    PROFESSIONAL("专业面试", 2),
    RESUME_DISCUSSION("简历探讨", 3),
    BEHAVIORAL("行为面试", 4),
    ENDING("结束", 5);

    private final String displayName;
    private final int order;

    InterviewPhase(String displayName, int order) {
        this.displayName = displayName;
        this.order = order;
    }

    public String getDisplayName() {
        return displayName;
    }

    public int getOrder() {
        return order;
    }

    /**
     * 按固定顺序对用户选择的环节排序，并自动追加 ENDING。
     */
    public static List<InterviewPhase> sortSelected(List<InterviewPhase> selected) {
        return Stream.concat(
                selected.stream().sorted(Comparator.comparingInt(InterviewPhase::getOrder)),
                Stream.of(ENDING)
        ).distinct().toList();
    }
}
