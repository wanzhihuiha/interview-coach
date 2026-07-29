package com.interviewcoach.position.domain.entity;

/**
 * 岗位审核状态枚举。
 */
public enum PositionAuditStatus {
    PENDING(0, "待审核"),
    APPROVED(1, "已通过"),
    REJECTED(2, "已拒绝");

    private final int code;
    private final String displayName;

    PositionAuditStatus(int code, String displayName) {
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
