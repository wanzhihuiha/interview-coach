package com.interviewcoach.position.infrastructure.async;

import com.interviewcoach.common.observability.DiagnosticContext;
import com.interviewcoach.position.application.event.PositionAnalysisRequestedEvent;
import com.interviewcoach.position.application.event.PositionAnalysisWaitingRemovedEvent;
import com.interviewcoach.position.application.port.PositionAnalysisQueueProjector;
import com.interviewcoach.position.infrastructure.config.PositionAnalysisProperties;
import com.interviewcoach.position.infrastructure.config.PositionTaskExecutorConfiguration;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 数据库提交后只把轻量入队工作交给有界执行器，HTTP 线程不等待 Redis 重试。
 * fallbackExecution 同时兼容“事件在事务内发布”和“数据库事务已经返回后发布”两条路径。
 * 投影失败不会回滚或改写 MySQL 任务；当前实现依赖告警、惰性清理和下次应用启动重建，不承诺运行中必达。
 */
@Slf4j
@Component
public class PositionAnalysisEnqueueEventListener {

    /** 承载 Redis 增删投影和有限重试的有界平台线程执行器。 */
    private final ExecutorService executor;
    /** 延迟取得实际 Redis 投影端口；组件缺失时保留 MySQL 事实并记录告警。 */
    private final ObjectProvider<PositionAnalysisQueueProjector> projectorProvider;
    /** 提供投影最大尝试次数及相邻尝试等待时间的解析配置。 */
    private final PositionAnalysisProperties properties;

    /**
     * 装配事务后投影监听器。
     *
     * @param executor Redis 投影专用有界执行器
     * @param projectorProvider Redis 投影端口提供者
     * @param properties 投影重试配置
     */
    public PositionAnalysisEnqueueEventListener(
            @Qualifier(PositionTaskExecutorConfiguration.ENQUEUE_EXECUTOR) ExecutorService executor,
            ObjectProvider<PositionAnalysisQueueProjector> projectorProvider,
            PositionAnalysisProperties properties) {
        this.executor = executor;
        this.projectorProvider = projectorProvider;
        this.properties = properties;
    }

    /**
     * 异步执行器提交失败时只保留 MySQL WAITING 事实并告警，不能把已提交事务伪装成 HTTP 失败。
     * requestId 在线程切换前显式捕获，任务线程结束时由 MDC 作用域统一恢复，避免线程复用串值。
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onRequested(PositionAnalysisRequestedEvent event) {
        String requestId = DiagnosticContext.currentRequestId();
        try {
            // 数据库已经提交，只把轻量 Redis 投影交给有界线程池，HTTP 调用不等待后续重试。
            executor.execute(() -> {
                try (DiagnosticContext.Scope ignored = DiagnosticContext.openPositionTask(
                        requestId, event.taskId(), event.positionId())) {
                    log.info("[PositionAnalysis] 数据库事务已提交，开始 Redis 队列投影: queueOwner={}",
                            event.queueOwner());
                    // 幂等入队失败时在同一后台任务内有限重试，耗尽后仍保留 MySQL WAITING。
                    enqueueWithRetry(event);
                }
            });
        } catch (RejectedExecutionException e) {
            log.error("[PositionAnalysis] 入队执行器拒绝任务，等待启动重建: "
                            + "taskId={}, positionId={}, queueOwner={}",
                    event.taskId(), event.positionId(), event.queueOwner(), e);
        }
    }

    /**
     * 归档或永久删除提交后异步移除 WAITING 投影；旧成员即使残留也会被 DB 领取校验清除。
     * 与入队相同，MDC 不依赖线程池自动传播。
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onWaitingRemoved(PositionAnalysisWaitingRemovedEvent event) {
        String requestId = DiagnosticContext.currentRequestId();
        try {
            // 删除事实已提交后异步清理 Redis WAITING 投影，执行器拒绝不会恢复数据库任务。
            executor.execute(() -> {
                try (DiagnosticContext.Scope ignored = DiagnosticContext.openPositionTask(
                        requestId, event.taskId(), null)) {
                    // 旧投影即使暂时残留，领取时仍需通过 MySQL 当前任务与源状态校验。
                    removeWaitingWithRetry(event);
                }
            });
        } catch (RejectedExecutionException e) {
            log.warn("[PositionAnalysis] 投影移除执行器繁忙，等待惰性清理或启动重建: "
                            + "taskId={}, queueOwner={}",
                    event.taskId(), event.queueOwner(), e);
        }
    }

    /**
     * 在有界执行器线程内重试 Redis 投影。重试耗尽只保留 MySQL WAITING 状态，
     * 不把任务改为失败；应用下次启动时会根据数据库事实重建队列。
     */
    private void enqueueWithRetry(PositionAnalysisRequestedEvent event) {
        // 投影实现未装配时不能生成 Redis 队列成员，只留下可在下次启动重建的 MySQL WAITING 事实。
        PositionAnalysisQueueProjector projector = projectorProvider.getIfAvailable();
        if (projector == null) {
            log.error("[PositionAnalysis] Redis 队列投影未装配，任务保留在 MySQL: queueOwner={}",
                    event.queueOwner());
            return;
        }

        for (int attempt = 1; attempt <= properties.getEnqueueMaxAttempts(); attempt++) {
            try {
                // 端口调用幂等写入任务投影并唤醒当前 JVM 调度器；成功后本事件结束。
                projector.enqueue(event.taskId(), event.queueOwner());
                log.info("[PositionAnalysis] Redis 队列投影完成: queueOwner={}, attempt={}",
                        event.queueOwner(), attempt);
                return;
            } catch (RuntimeException e) {
                if (attempt == properties.getEnqueueMaxAttempts()) {
                    log.error("[PositionAnalysis] Redis 入队重试耗尽，任务等待启动重建: "
                                    + "queueOwner={}, attempts={}, errorType={}, errorMessage={}",
                            event.queueOwner(), attempt, e.getClass().getSimpleName(),
                            errorMessage(e), e);
                    return;
                }
                log.debug("[PositionAnalysis] Redis 入队失败，准备重试: queueOwner={}, "
                                + "attempt={}, errorType={}",
                        event.queueOwner(), attempt, e.getClass().getSimpleName(), e);
                // 相邻尝试按配置暂停；中断时恢复线程标记并停止当前投影任务。
                if (!pauseBeforeRetry(properties.getEnqueueRetryDelay())) {
                    log.warn("[PositionAnalysis] Redis 入队线程被中断，任务等待启动重建: "
                                    + "queueOwner={}, attempt={}",
                            event.queueOwner(), attempt);
                    return;
                }
            }
        }
    }

    /**
     * 尝试清除已失效的 WAITING 投影。移除失败不会恢复数据库状态，后续 DB 领取校验或启动重建会清理旧成员。
     */
    private void removeWaitingWithRetry(PositionAnalysisWaitingRemovedEvent event) {
        // 没有投影实现时不影响已提交的删除事实，旧 Redis 成员留给后续惰性清理或启动重建。
        PositionAnalysisQueueProjector projector = projectorProvider.getIfAvailable();
        if (projector == null) {
            log.warn("[PositionAnalysis] Redis 队列投影尚未装配，等待启动重建: taskId={}",
                    event.taskId());
            return;
        }

        for (int attempt = 1; attempt <= properties.getEnqueueMaxAttempts(); attempt++) {
            try {
                // 按 taskId 和 queueOwner 幂等移除 WAITING 投影，不触碰可能仍 busy 的 Worker 任务。
                projector.removeWaiting(event.taskId(), event.queueOwner());
                log.debug("[PositionAnalysis] Redis WAITING 投影已移除: queueOwner={}, attempt={}",
                        event.queueOwner(), attempt);
                return;
            } catch (RuntimeException e) {
                if (attempt == properties.getEnqueueMaxAttempts()) {
                    log.warn("[PositionAnalysis] Redis 投影移除重试耗尽，等待惰性清理或启动重建: "
                                    + "queueOwner={}, attempts={}, errorType={}, errorMessage={}",
                            event.queueOwner(), attempt, e.getClass().getSimpleName(),
                            errorMessage(e), e);
                    return;
                }
                log.debug("[PositionAnalysis] Redis 投影移除失败，准备重试: queueOwner={}, "
                                + "attempt={}, errorType={}",
                        event.queueOwner(), attempt, e.getClass().getSimpleName(), e);
                if (!pauseBeforeRetry(properties.getEnqueueRetryDelay())) {
                    log.warn("[PositionAnalysis] Redis 投影移除线程被中断: queueOwner={}, attempt={}",
                            event.queueOwner(), attempt);
                    return;
                }
            }
        }
    }

    /**
     * 重试等待被中断时恢复中断标记，由当前投影任务停止重试并交给启动恢复收口。
     */
    private boolean pauseBeforeRetry(Duration delay) {
        if (delay.isZero()) {
            return true;
        }
        try {
            Thread.sleep(delay);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * 提取至多 20 层异常链中的末端消息，并将日志文本截为 500 个 UTF-16 字符；两项精确取值依据缺失。
     */
    private String errorMessage(Throwable error) {
        Throwable current = error;
        for (int depth = 0; current.getCause() != null && depth < 20; depth++) {
            current = current.getCause();
        }
        String message = current.getMessage();
        if (message == null || message.isBlank()) {
            return "-";
        }
        String value = message.replace("\r", "\\r").replace("\n", "\\n");
        return value.length() <= 500 ? value : value.substring(0, 500) + "...(truncated)";
    }
}
