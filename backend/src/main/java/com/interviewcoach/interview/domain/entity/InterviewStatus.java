package com.interviewcoach.interview.domain.entity;

/**
 * 面试会话状态。
 */
public enum InterviewStatus {
    IN_PROGRESS("进行中"),
    ENDED("已结束"),
    INTERRUPTED("已中断");

    private final String displayName;

    InterviewStatus(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
