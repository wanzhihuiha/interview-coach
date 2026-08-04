package com.interviewcoach.position.infrastructure.async;

import com.interviewcoach.common.observability.DiagnosticContext;
import com.interviewcoach.position.application.service.PositionAnalysisStateService;
import com.interviewcoach.position.application.service.PositionAnalysisStateService.AnalysisInput;
import com.interviewcoach.position.application.service.PositionAnalysisWorker;
import com.interviewcoach.position.domain.exception.PositionAnalysisFailureCode;
import com.interviewcoach.position.infrastructure.config.PositionAnalysisProperties;
import com.interviewcoach.position.infrastructure.config.PositionTaskExecutorConfiguration;
import com.interviewcoach.position.infrastructure.redis.PositionAnalysisRedisQueue;
import com.interviewcoach.position.infrastructure.redis.PositionAnalysisRedisQueue.Reservation;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * 单实例岗位调度器：串行预留队首任务，取得本地容量后才创建虚拟线程 Worker。
 * Redis 负责公平预留和参与者占用，MySQL 领取结果决定任务是否真正进入 RUNNING。
 */
@Slf4j
@Component
public class PositionAnalysisDispatcher implements SmartLifecycle {

    private final ScheduledExecutorService dispatchExecutor;
    private final ExecutorService workerExecutor;
    private final PositionAnalysisRedisQueue queue;
    private final PositionAnalysisStateService stateService;
    private final PositionAnalysisWorker worker;
    private final PositionAnalysisProperties properties;
    private final Semaphore permits;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean drainScheduled = new AtomicBoolean(false);
    private final Object lifecycleMonitor = new Object();

    public PositionAnalysisDispatcher(
            @Qualifier(PositionTaskExecutorConfiguration.DISPATCH_EXECUTOR)
            ScheduledExecutorService dispatchExecutor,
            @Qualifier(PositionTaskExecutorConfiguration.WORKER_EXECUTOR)
            ExecutorService workerExecutor,
            PositionAnalysisRedisQueue queue,
            PositionAnalysisStateService stateService,
            PositionAnalysisWorker worker,
            PositionAnalysisProperties properties) {
        this.dispatchExecutor = dispatchExecutor;
        this.workerExecutor = workerExecutor;
        this.queue = queue;
        this.stateService = stateService;
        this.worker = worker;
        this.properties = properties;
        this.permits = new Semaphore(properties.getMaxConcurrency(), true);
    }

    /**
     * 入队和 Worker 完成都只发出幂等唤醒；未完成启动恢复时不会领取任务。
     */
    public void wake() {
        if (!running.get() || !drainScheduled.compareAndSet(false, true)) {
            return;
        }
        try {
            dispatchExecutor.execute(this::drain);
        } catch (RejectedExecutionException e) {
            drainScheduled.set(false);
            if (running.get()) {
                log.warn("[PositionAnalysis] 调度执行器拒绝唤醒: errorType={}",
                        e.getClass().getSimpleName());
            }
        }
    }

    /**
     * 仅由启动恢复 Runner 在 DB 收口和 Redis 重建全部成功后开放领取。
     */
    @Override
    public void start() {
        synchronized (lifecycleMonitor) {
            if (running.compareAndSet(false, true)) {
                log.info("[PositionAnalysis] 岗位调度器已开放: maxConcurrency={}",
                        properties.getMaxConcurrency());
            } else {
                return;
            }
        }
        wake();
    }

    /**
     * 关闭时先阻止新领取；已运行 Worker 仍负责真实结束后的状态和容量收口。
     */
    @Override
    public void stop() {
        synchronized (lifecycleMonitor) {
            if (running.compareAndSet(true, false)) {
                log.info("[PositionAnalysis] 岗位调度器已停止新领取");
            }
        }
    }

    /**
     * SmartLifecycle 的异步停止回调只等待“禁止新领取”完成，不等待已经运行的 Worker。
     */
    @Override
    public void stop(Runnable callback) {
        stop();
        callback.run();
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    /**
     * 禁止容器自动启动；必须由恢复 Runner 在 DB 状态收口和 Redis 重建成功后显式开放。
     */
    @Override
    public boolean isAutoStartup() {
        return false;
    }

    /**
     * 使用最高生命周期阶段，使容器关闭时优先禁止新领取，再由后续组件完成资源销毁。
     */
    @Override
    public int getPhase() {
        return Integer.MAX_VALUE;
    }

    /**
     * 单线程循环按“本地许可 -> Redis 预留 -> MySQL 领取 -> 虚拟线程”顺序推进任务。
     * DB 领取未确认时先恢复 Redis 预留再释放本地许可；DB 已判定旧任务时清理预留后继续。
     */
    private void drain() {
        boolean retryLater = false;
        try {
            while (running.get() && permits.tryAcquire()) {
                Reservation reservation;
                try {
                    reservation = queue.reserveNext();
                } catch (RuntimeException e) {
                    permits.release();
                    retryLater = true;
                    log.warn("[PositionAnalysis] Redis 领取失败，稍后重试: "
                                    + "errorType={}, errorMessage={}",
                            e.getClass().getSimpleName(), errorMessage(e));
                    break;
                }
                if (reservation == null) {
                    permits.release();
                    break;
                }
                log.debug("[PositionAnalysis] Redis 任务预留成功: taskId={}, queueOwner={}",
                        reservation.taskId(), reservation.queueOwner());

                AnalysisInput input = null;
                RuntimeException startFailure = null;
                boolean stoppedBeforeStart = false;
                synchronized (lifecycleMonitor) {
                    if (!running.get()) {
                        safelyRestore(reservation);
                        stoppedBeforeStart = true;
                    } else {
                        try {
                            input = stateService.start(reservation.taskId());
                        } catch (RuntimeException e) {
                            startFailure = e;
                        }
                    }
                }
                if (stoppedBeforeStart) {
                    permits.release();
                    break;
                }
                if (startFailure != null) {
                    safelyRestore(reservation);
                    permits.release();
                    retryLater = true;
                    log.warn("[PositionAnalysis] DB 领取未确认，已恢复 Redis 预留: "
                                    + "taskId={}, queueOwner={}, errorType={}, errorMessage={}",
                            reservation.taskId(), reservation.queueOwner(),
                            startFailure.getClass().getSimpleName(), errorMessage(startFailure));
                    break;
                }
                if (input == null) {
                    log.debug("[PositionAnalysis] 数据库任务已失效，清理 Redis 旧预留: "
                                    + "taskId={}, queueOwner={}",
                            reservation.taskId(), reservation.queueOwner());
                    safelyFinish(reservation);
                    permits.release();
                    continue;
                }
                if (!Objects.equals(input.queueOwner(), reservation.queueOwner())) {
                    boolean stateClosed = markSchedulingFailed(
                            input.taskId(),
                            PositionAnalysisFailureCode.QUEUE_RESERVATION_INVALID,
                            "任务排队归属已变化，请重新解析");
                    finishIfStateClosed(reservation, stateClosed);
                    permits.release();
                    continue;
                }

                log.info("[PositionAnalysis] 任务领取完成: taskId={}, positionId={}, "
                                + "requestUserId={}, queueOwner={}, state=WAITING->RUNNING",
                        input.taskId(), input.positionId(), input.requestUserId(), input.queueOwner());

                AnalysisInput workerInput = input;
                try {
                    workerExecutor.execute(() -> runWorker(workerInput, reservation));
                    log.debug("[PositionAnalysis] Worker 已提交: taskId={}, positionId={}, queueOwner={}",
                            workerInput.taskId(), workerInput.positionId(), workerInput.queueOwner());
                } catch (RuntimeException e) {
                    log.error("[PositionAnalysis] 虚拟线程提交失败: taskId={}, errorType={}",
                            workerInput.taskId(), e.getClass().getSimpleName(), e);
                    boolean stateClosed = markSchedulingFailed(
                            workerInput.taskId(),
                            PositionAnalysisFailureCode.WORKER_SUBMISSION_FAILED,
                            "岗位解析调度失败，请重新解析");
                    finishIfStateClosed(reservation, stateClosed);
                    permits.release();
                }
            }
        } finally {
            drainScheduled.set(false);
            if (retryLater) {
                scheduleRetry(properties.getDispatchRetryDelay());
            } else if (running.get() && permits.availablePermits() > 0) {
                wakeIfReady();
            }
        }
    }

    /**
     * 在 Worker 线程内重新建立 taskId/positionId MDC，并在所有退出路径释放本地容量后再次唤醒调度。
     * 只有数据库已写入终态时才释放 Redis busy；写回失败则保留占用，交由启动恢复处理遗留 RUNNING。
     */
    private void runWorker(AnalysisInput input, Reservation reservation) {
        try (DiagnosticContext.Scope ignored = DiagnosticContext.openPositionTask(
                null, input.taskId(), input.positionId())) {
            log.info("[PositionAnalysis] Worker 开始: requestUserId={}, queueOwner={}, jdLength={}",
                    input.requestUserId(),
                    input.queueOwner(),
                    input.jdContent() == null ? 0 : input.jdContent().length());
            if (log.isDebugEnabled()) {
                log.debug("[PositionAnalysis] Worker 输入详情: jobCategory={}, jdContent={}",
                        input.jobCategory(), debugContent(input.jdContent()));
            }
            boolean stateClosed = false;
            try {
                stateClosed = worker.execute(input);
            } finally {
                try {
                    finishIfStateClosed(reservation, stateClosed);
                } finally {
                    permits.release();
                    wake();
                }
            }
        }
    }

    /**
     * 尝试把“调度阶段失败”写入数据库终态。
     *
     * @return {@code true} 表示数据库已经收口，可以释放 Redis busy；否则调用方必须保留占用
     */
    private boolean markSchedulingFailed(
            Long taskId, PositionAnalysisFailureCode failureCode, String message) {
        try {
            stateService.fail(taskId, failureCode.name(), message);
            return true;
        } catch (RuntimeException e) {
            log.error("[PositionAnalysis] 调度失败状态写回失败: taskId={}, "
                            + "failureCode={}, errorType={}",
                    taskId, failureCode, e.getClass().getSimpleName(), e);
            return false;
        }
    }

    /**
     * 数据库未确认收口时保留 busy，避免同一参与者继续领取；启动恢复会处理遗留 RUNNING。
     */
    private void finishIfStateClosed(Reservation reservation, boolean stateClosed) {
        if (stateClosed) {
            safelyFinish(reservation);
            return;
        }
        log.error("[PositionAnalysis] 数据库状态未收口，保留 Redis busy 等待重启恢复: taskId={}",
                reservation.taskId());
    }

    /**
     * 按当前预留身份原子释放 Redis busy；预留已变化时不触碰新持有者，异常留给启动重建兜底。
     */
    private void safelyFinish(Reservation reservation) {
        try {
            if (!queue.finish(reservation)) {
                log.warn("[PositionAnalysis] Redis busy 已变化，跳过旧预留释放: taskId={}",
                        reservation.taskId());
            } else {
                log.debug("[PositionAnalysis] Redis busy 已释放: taskId={}, queueOwner={}",
                        reservation.taskId(), reservation.queueOwner());
            }
        } catch (RuntimeException e) {
            log.warn("[PositionAnalysis] Redis 参与者释放失败，等待重启重建: "
                            + "taskId={}, queueOwner={}, errorType={}, errorMessage={}",
                    reservation.taskId(), reservation.queueOwner(),
                    e.getClass().getSimpleName(), errorMessage(e));
        }
    }

    /**
     * 仅在数据库尚未确认领取时把预留恢复到 WAITING；恢复失败时不猜测状态，等待启动重建。
     */
    private void safelyRestore(Reservation reservation) {
        try {
            if (!queue.restore(reservation)) {
                log.warn("[PositionAnalysis] Redis 预留已变化，无法恢复任务: taskId={}",
                        reservation.taskId());
            } else {
                log.debug("[PositionAnalysis] Redis 任务预留已恢复: taskId={}, queueOwner={}",
                        reservation.taskId(), reservation.queueOwner());
            }
        } catch (RuntimeException e) {
            log.warn("[PositionAnalysis] Redis 预留恢复失败，等待重启重建: "
                            + "taskId={}, queueOwner={}, errorType={}, errorMessage={}",
                    reservation.taskId(), reservation.queueOwner(),
                    e.getClass().getSimpleName(), errorMessage(e));
        }
    }

    /**
     * 本地仍有容量时检查 Redis 是否存在 ready 参与者；检查失败改为延迟重试，避免 Worker 线程空转。
     */
    private void wakeIfReady() {
        try {
            if (queue.hasReadyParticipant()) {
                wake();
            }
        } catch (RuntimeException e) {
            log.warn("[PositionAnalysis] Redis ready 检查失败，稍后重试: "
                            + "errorType={}, errorMessage={}",
                    e.getClass().getSimpleName(), errorMessage(e));
            scheduleRetry(properties.getDispatchRetryDelay());
        }
    }

    /**
     * 延迟任务只负责再次调用幂等 wake，停止期间不再创建新的重试链。
     */
    private void scheduleRetry(Duration delay) {
        if (!running.get()) {
            return;
        }
        try {
            dispatchExecutor.schedule(this::wake, delay.toNanos(), TimeUnit.NANOSECONDS);
        } catch (RejectedExecutionException e) {
            if (running.get()) {
                log.warn("[PositionAnalysis] 调度重试被拒绝: errorType={}",
                        e.getClass().getSimpleName());
            }
        }
    }

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

    private String debugContent(String value) {
        if (value == null) {
            return "<null>";
        }
        String singleLine = value.replace("\r", "\\r").replace("\n", "\\n");
        return singleLine.length() <= 5000
                ? singleLine
                : singleLine.substring(0, 5000)
                        + "...(truncated,totalLength=" + singleLine.length() + ")";
    }
}
