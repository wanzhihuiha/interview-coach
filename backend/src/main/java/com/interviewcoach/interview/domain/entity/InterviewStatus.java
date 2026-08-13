package com.interviewcoach.interview.domain.entity;

/**
 * 面试会话的用户可见生命周期状态；英文编码持久化并返回 API，中文名作为现有状态 Label。
 */
public enum InterviewStatus {
    /** 会话已创建且允许提交回答；创建中间态也使用该值。 */
    IN_PROGRESS("进行中"),

    /** Coordinator 自然推进到结束环节后写入的正常终态。 */
    ENDED("已结束"),

    /** 用户主动结束、创建补偿或当前进程启动清理写入的中断终态。 */
    INTERRUPTED("已中断");

    /** API 返回的中文状态名称。 */
    private final String displayName;

    /** 绑定稳定英文枚举值与中文展示名。 */
    InterviewStatus(String displayName) {
        this.displayName = displayName;
    }

    /** 返回详情和创建响应使用的中文状态 Label。 */
    public String getDisplayName() {
        return displayName;
    }
}
