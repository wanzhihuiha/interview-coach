package com.interviewcoach.position.domain.entity;

/**
 * V1 岗位审核列的兼容枚举，用于读取既有表结构中的历史值。
 * 当前个人/公共岗位解析、发布和资源权限不以该列判断，新业务不得把它当作现行状态机。
 */
public enum PositionAuditStatus {
    /** 历史审核流程尚未处理的记录；当前新建岗位仅为兼容旧列写入该默认值。 */
    PENDING(0, "待审核"),
    /** 历史审核流程标记为通过的记录；不代表当前公共岗位已具备正式画像或可见。 */
    APPROVED(1, "已通过"),
    /** 历史审核流程标记为拒绝的记录；当前新业务不会据此决定岗位解析结果。 */
    REJECTED(2, "已拒绝");

    /** 旧接口或数据模型保留的历史数值编码，不作为当前任务状态编码。 */
    private final int code;
    /** 历史审核状态的中文展示名称。 */
    private final String displayName;

    PositionAuditStatus(int code, String displayName) {
        this.code = code;
        this.displayName = displayName;
    }

    /** 返回兼容旧模型的历史数值编码。 */
    public int getCode() {
        return code;
    }

    /** 返回历史审核状态的中文展示名称。 */
    public String getDisplayName() {
        return displayName;
    }
}
