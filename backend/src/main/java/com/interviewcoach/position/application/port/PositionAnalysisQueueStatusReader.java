package com.interviewcoach.position.application.port;

/**
 * 读取 Redis 公平调度投影的等待量快照，由状态响应编排调用、Redis 队列实现。
 * 该端口不提供任务状态事实，也不承诺任务开始或完成时间。
 */
public interface PositionAnalysisQueueStatusReader {

    /**
     * 估算当前等待任务前方的 busy 和轮转任务量。
     *
     * @param taskId 等待任务 ID
     * @param queueOwner 任务所属队列参与者标识
     * @return 本次读取时的估算值；恢复未完成、任务不在投影中或无法计算时为空
     */
    Long queueAhead(Long taskId, String queueOwner);
}
