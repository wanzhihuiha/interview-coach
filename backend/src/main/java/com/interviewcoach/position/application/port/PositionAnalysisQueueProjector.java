package com.interviewcoach.position.application.port;

/**
 * 把 MySQL 中已提交的 WAITING 任务幂等投影到可重建的 Redis 公平队列。
 */
public interface PositionAnalysisQueueProjector {

    void enqueue(Long taskId, String queueOwner);

    void removeWaiting(Long taskId, String queueOwner);
}
