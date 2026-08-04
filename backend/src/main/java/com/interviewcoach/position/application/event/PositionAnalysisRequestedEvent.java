package com.interviewcoach.position.application.event;

/**
 * 岗位状态事务提交后投影到 Redis 公平队列的轻量事件。
 */
public record PositionAnalysisRequestedEvent(Long taskId, Long positionId, String queueOwner) {
}
