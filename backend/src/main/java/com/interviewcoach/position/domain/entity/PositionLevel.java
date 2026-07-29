package com.interviewcoach.position.domain.entity;

import java.util.Locale;

/**
 * 岗位职级编码及中文名称。
 */
public enum PositionLevel {
    JUNIOR("初级"),
    MID("中级"),
    SENIOR("高级"),
    EXPERT("专家");

    private final String displayName;

    PositionLevel(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }

    /**
     * 将岗位职级编码转换为中文；模型已返回中文或未知值时保持原样。
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
