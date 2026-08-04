package com.interviewcoach.position.domain.entity;

/**
 * 岗位解析当前任务状态。
 */
public enum PositionAnalysisTaskStatus {
    WAITING("排队中"),
    RUNNING("解析中"),
    SUCCEEDED("待确认"),
    FAILED("解析失败");

    private final String displayName;

    PositionAnalysisTaskStatus(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
