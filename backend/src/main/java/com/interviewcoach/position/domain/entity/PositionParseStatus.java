package com.interviewcoach.position.domain.entity;

/**
 * 岗位解析状态枚举。
 */
public enum PositionParseStatus {
    PENDING(0),
    PARSING(1),
    PENDING_CONFIRM(2),
    CONFIRMED(3),
    PARSE_FAILED(4);

    private final int code;

    PositionParseStatus(int code) {
        this.code = code;
    }

    public int getCode() {
        return code;
    }
}
