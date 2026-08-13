package com.interviewcoach.position.application.port;

/**
 * MySQL 当前任务与 Redis 公平调度投影之间的应用端口，由事务后监听器调用、Redis 适配器实现。
 * 投影可根据数据库快照重建；任一方法失败都不回滚或改写已经提交的 MySQL 任务事实。
 */
public interface PositionAnalysisQueueProjector {

    /**
     * 幂等加入已提交的 WAITING 任务，并在成功后唤醒当前进程的调度器。
     *
     * @param taskId 数据库当前任务 ID
     * @param queueOwner 服务端生成的队列参与者标识
     */
    void enqueue(Long taskId, String queueOwner);

    /**
     * 移除数据库中已不存在的 WAITING 任务投影；失败时数据库删除结果保持不变。
     *
     * @param taskId 已删除的等待任务 ID
     * @param queueOwner 任务原先所属的队列参与者标识
     */
    void removeWaiting(Long taskId, String queueOwner);
}
