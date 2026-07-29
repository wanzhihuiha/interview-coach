package com.interviewcoach.position.domain.entity;

/**
 * 岗位解析状态枚举。
 */
public enum PositionParseStatus {
    PENDING(0, "待解析"),
    PARSING(1, "解析中"),
    PENDING_CONFIRM(2, "待确认"),
    CONFIRMED(3, "已确认"),
    PARSE_FAILED(4, "解析失败");

    private final int code;
    private final String displayName;

    PositionParseStatus(int code, String displayName) {
        this.code = code;
        this.displayName = displayName;
    }

    public int getCode() {
        return code;
    }

    public String getDisplayName() {
        return displayName;
    }
}
