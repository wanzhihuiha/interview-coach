package com.interviewcoach.position.application.port;

/**
 * 读取 Redis 公平投影中的大致等待量；任务状态仍以 MySQL 为准。
 */
public interface PositionAnalysisQueueStatusReader {

    Long queueAhead(Long taskId, String queueOwner);
}
