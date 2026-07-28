package com.interviewcoach.position.domain.entity;

/**
 * 岗位审核状态枚举。
 */
public enum PositionAuditStatus {
    PENDING(0),
    APPROVED(1),
    REJECTED(2);

    private final int code;

    PositionAuditStatus(int code) {
        this.code = code;
    }

    public int getCode() {
        return code;
    }
}
