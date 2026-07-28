package com.interviewcoach.resume.domain.entity;

/**
 * 简历解析状态枚举。
 */
public enum ResumeParseStatus {
    PENDING("PENDING"),
    PARSING("PARSING"),
    PENDING_CONFIRM("PENDING_CONFIRM"),
    CONFIRMED("CONFIRMED"),
    PARSE_FAILED("PARSE_FAILED");

    private final String value;

    ResumeParseStatus(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }
}
