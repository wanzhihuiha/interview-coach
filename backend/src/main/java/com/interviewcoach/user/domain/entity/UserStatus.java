package com.interviewcoach.user.domain.entity;

/**
 * 用户状态枚举。
 */
public enum UserStatus {
    /**
     * 正常
     */
    ACTIVE("正常"),

    /**
     * 禁用
     */
    DISABLED("禁用");

    private final String displayName;

    UserStatus(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
