package com.interviewcoach.resume.domain.entity;

/**
 * 简历解析状态枚举。
 */
public enum ResumeParseStatus {
    PENDING("PENDING", "待解析"),
    PARSING("PARSING", "解析中"),
    PENDING_CONFIRM("PENDING_CONFIRM", "待确认"),
    CONFIRMED("CONFIRMED", "已确认"),
    PARSE_FAILED("PARSE_FAILED", "解析失败");

    private final String value;
    private final String displayName;

    ResumeParseStatus(String value, String displayName) {
        this.value = value;
        this.displayName = displayName;
    }

    public String getValue() {
        return value;
    }

    public String getDisplayName() {
        return displayName;
    }
}
