package com.interviewcoach.position.domain.entity;

/**
 * V1 岗位解析列的兼容枚举，用于读取既有表结构中的历史值。
 * 新解析任务的排队、运行、候选和失败事实由 {@link PositionAnalysisTaskStatus} 承载，不得从本枚举推导当前任务状态。
 */
public enum PositionParseStatus {
    /** 历史流程尚未开始解析；当前新建岗位仅为兼容旧列写入该默认值。 */
    PENDING(0, "待解析"),
    /** 历史流程正在解析；不代表当前任务表中一定存在 RUNNING 任务。 */
    PARSING(1, "解析中"),
    /** 历史流程已有待确认结果；当前候选事实应读取当前任务表。 */
    PENDING_CONFIRM(2, "待确认"),
    /** 历史流程已经确认；当前正式画像是否可用应查询正式画像表。 */
    CONFIRMED(3, "已确认"),
    /** 历史流程解析失败；当前失败分类和消息应读取当前任务表。 */
    PARSE_FAILED(4, "解析失败");

    /** 旧接口或数据模型保留的历史数值编码，不作为当前任务稳定编码。 */
    private final int code;
    /** 历史解析状态的中文展示名称。 */
    private final String displayName;

    PositionParseStatus(int code, String displayName) {
        this.code = code;
        this.displayName = displayName;
    }

    /** 返回兼容旧模型的历史数值编码。 */
    public int getCode() {
        return code;
    }

    /** 返回历史解析状态的中文展示名称。 */
    public String getDisplayName() {
        return displayName;
    }
}
