package com.interviewcoach.position.domain.entity;

/**
 * 岗位解析当前任务状态。
 */
public enum PositionAnalysisTaskStatus {
    /** MySQL 已登记任务但调度器尚未成功领取，Redis 可以保存其可重建等待投影。 */
    WAITING("排队中"),

    /** 调度器已按期望源状态领取，Worker 正在或将要执行模型调用。 */
    RUNNING("解析中"),

    /** Worker 已写回合法候选画像，等待有权限的调用方按当前 taskId 确认。 */
    SUCCEEDED("待确认"),

    /** 解析、调度或恢复已经以稳定错误分类终结，活动岗位可以重新发起任务。 */
    FAILED("解析失败");

    /** API 状态展示使用的中文名称；稳定状态判断仍使用枚举编码。 */
    private final String displayName;

    PositionAnalysisTaskStatus(String displayName) {
        this.displayName = displayName;
    }

    /** 返回当前任务状态面向接口展示的中文名称。 */
    public String getDisplayName() {
        return displayName;
    }
}
