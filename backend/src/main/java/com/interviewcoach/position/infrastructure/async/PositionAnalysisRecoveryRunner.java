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
 * 当前应用进程的岗位解析启动恢复入口。
 * Spring Boot 启动回调先收口 MySQL 任务事实，再按快照重建 Redis 投影，全部成功后才开放当前 JVM 的任务领取。
 * 任何一步抛出异常都会阻止后续调度启动；本类不证明部署为单实例，也不协调多个实例的恢复所有权。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PositionAnalysisRecoveryRunner implements ApplicationRunner {

    /** 负责扫描并收口 MySQL 当前任务事实的事务服务。 */
    private final PositionAnalysisRecoveryService recoveryService;

    /** 负责以 MySQL 等待任务快照重建岗位 Redis 队列投影的组件。 */
    private final PositionAnalysisRedisQueue queue;

    /** 在恢复成功后开放当前 JVM 领取循环的本地调度器。 */
    private final PositionAnalysisDispatcher dispatcher;

    /**
     * 按数据库收口、Redis 重建、本地调度启动的不可交换顺序完成当前进程恢复。
     *
     * @param args Spring Boot 启动参数；岗位恢复流程不读取其中内容
     */
    @Override
    public void run(ApplicationArguments args) {
        // 先以 MySQL 为事实源删除无效任务、收口遗留 RUNNING，并形成有效 WAITING 快照。
        RecoveryResult result = recoveryService.recover();

        // 再清理并按数据库快照整体重建 Redis 投影；失败会中止启动，不开放未知状态下的领取。
        queue.resetAndRestore(result.waitingTasks().stream()
                .map(task -> new QueueTask(task.taskId(), task.queueOwner()))
                .toList());
        log.info("[PositionAnalysis] 启动恢复完成: waitingCount={}, interruptedCount={}, "
                        + "removedCount={}, invalidCount={}",
                result.waitingTasks().size(),
                result.interruptedCount(),
                result.removedCount(),
                result.invalidCount());

        // 只有数据库和 Redis 都恢复成功，才允许当前 JVM 的调度器开始领取任务。
        dispatcher.start();
    }
}
