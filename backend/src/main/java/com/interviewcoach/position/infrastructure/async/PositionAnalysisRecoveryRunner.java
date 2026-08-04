package com.interviewcoach.position.infrastructure.async;

import com.interviewcoach.position.application.service.PositionAnalysisRecoveryService;
import com.interviewcoach.position.application.service.PositionAnalysisRecoveryService.RecoveryResult;
import com.interviewcoach.position.infrastructure.redis.PositionAnalysisRedisQueue;
import com.interviewcoach.position.infrastructure.redis.PositionAnalysisRedisQueue.QueueTask;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 启动时先收口 MySQL、重建 Redis，全部成功后才开放岗位任务领取。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PositionAnalysisRecoveryRunner implements ApplicationRunner {

    private final PositionAnalysisRecoveryService recoveryService;
    private final PositionAnalysisRedisQueue queue;
    private final PositionAnalysisDispatcher dispatcher;

    @Override
    public void run(ApplicationArguments args) {
        RecoveryResult result = recoveryService.recover();
        queue.resetAndRestore(result.waitingTasks().stream()
                .map(task -> new QueueTask(task.taskId(), task.queueOwner()))
                .toList());
        log.info("[PositionAnalysis] 启动恢复完成: waitingCount={}, interruptedCount={}, "
                        + "removedCount={}, invalidCount={}",
                result.waitingTasks().size(),
                result.interruptedCount(),
                result.removedCount(),
                result.invalidCount());
        dispatcher.start();
    }
}
