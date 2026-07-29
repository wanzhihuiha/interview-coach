package com.interviewcoach.resume.domain.entity;

import java.util.Locale;

/**
 * 候选人目标岗位相关经验等级。
 */
public enum ExperienceLevel {
    JUNIOR("初级"),
    MID("中级"),
    SENIOR("高级");

    private final String displayName;

    ExperienceLevel(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }

    /**
     * 将经验等级编码转换为中文；未知值保持原样以兼容历史数据。
     */
    public static String displayNameOf(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT)).getDisplayName();
        } catch (IllegalArgumentException e) {
            return value;
        }
    }
}
