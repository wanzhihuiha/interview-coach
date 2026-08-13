package com.interviewcoach.position.application.event;

/**
 * MySQL 中的 WAITING 当前任务已经删除后，请求异步移除其 Redis 队列投影的轻量事件。
 * Redis 清理失败不会恢复数据库任务，残留投影可由领取校验或后续启动重建清除。
 *
 * @param taskId 已从数据库删除的等待任务 ID
 * @param queueOwner 该任务原先所属的公共或个人队列参与者标识
 */
public record PositionAnalysisWaitingRemovedEvent(Long taskId, String queueOwner) {
}
