package com.interviewcoach.position.application.event;

/**
 * WAITING 任务在数据库提交删除后移除 Redis 投影的轻量事件。
 */
public record PositionAnalysisWaitingRemovedEvent(Long taskId, String queueOwner) {
}
