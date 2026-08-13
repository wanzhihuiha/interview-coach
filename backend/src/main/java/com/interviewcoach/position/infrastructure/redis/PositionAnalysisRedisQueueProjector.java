package com.interviewcoach.position.infrastructure.redis;

import com.interviewcoach.position.application.port.PositionAnalysisQueueProjector;
import com.interviewcoach.position.infrastructure.async.PositionAnalysisDispatcher;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * MySQL 事务后事件的岗位 Redis 队列投影适配器。
 * 成功增删投影后只唤醒当前 JVM 调度器，不直接创建 Worker；投影删除不会改变数据库任务事实。
 * 多实例环境中只唤醒本地调度器是否符合整体所有权设计，当前证据不足。
 */
@Component
@RequiredArgsConstructor
public class PositionAnalysisRedisQueueProjector implements PositionAnalysisQueueProjector {

    /** 维护岗位 ready、busy、参与者索引和参与者任务集合的 Redis 适配器。 */
    private final PositionAnalysisRedisQueue queue;

    /** 投影变化后需要唤醒的当前 JVM 岗位调度器。 */
    private final PositionAnalysisDispatcher dispatcher;

    /**
     * 幂等写入 WAITING 任务的 Redis 队列投影，并唤醒本地领取循环。
     *
     * @param taskId MySQL 当前任务 ID
     * @param queueOwner 服务端生成的个人或公共队列参与者
     */
    @Override
    public void enqueue(Long taskId, String queueOwner) {
        // 先完成 Redis 投影，失败时不唤醒调度器且不改变已经提交的 MySQL WAITING 事实。
        queue.enqueue(taskId, queueOwner);
        dispatcher.wake();
    }

    /**
     * 移除已经从 MySQL 删除的 WAITING 任务投影，并唤醒本地调度器继续检查队列。
     *
     * @param taskId 已删除或不再等待的 MySQL 当前任务 ID
     * @param queueOwner 该任务原来的队列参与者
     */
    @Override
    public void removeWaiting(Long taskId, String queueOwner) {
        // Redis 清理失败只留下可恢复的旧投影，不会恢复或删除任何 MySQL 任务。
        queue.removeWaiting(taskId, queueOwner);
        dispatcher.wake();
    }
}
