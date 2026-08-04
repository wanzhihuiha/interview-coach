package com.interviewcoach.position.infrastructure.redis;

import com.interviewcoach.position.application.port.PositionAnalysisQueueProjector;
import com.interviewcoach.position.infrastructure.async.PositionAnalysisDispatcher;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 事务后事件的 Redis 投影适配器；成功投影后只唤醒本地调度器，不直接创建 Worker。
 */
@Component
@RequiredArgsConstructor
public class PositionAnalysisRedisQueueProjector implements PositionAnalysisQueueProjector {

    private final PositionAnalysisRedisQueue queue;
    private final PositionAnalysisDispatcher dispatcher;

    @Override
    public void enqueue(Long taskId, String queueOwner) {
        queue.enqueue(taskId, queueOwner);
        dispatcher.wake();
    }

    @Override
    public void removeWaiting(Long taskId, String queueOwner) {
        queue.removeWaiting(taskId, queueOwner);
        dispatcher.wake();
    }
}
