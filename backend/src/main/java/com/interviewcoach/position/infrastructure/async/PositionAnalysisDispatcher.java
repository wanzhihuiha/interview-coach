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
 * 当前 JVM 的岗位解析调度器：取得本地容量后串行预留队首任务，再创建虚拟线程 Worker。
 * Redis 只负责公平预留和参与者占用，MySQL 条件更新决定任务是否真正进入 RUNNING；本类不证明部署为单实例。
 * 当前实现没有跨实例调度租约或全局并发许可，多实例环境的整体领取所有权证据不足。
 */
@Slf4j
@Component
public class PositionAnalysisDispatcher implements SmartLifecycle {

    /** 当前 JVM 串行执行领取循环和延迟重试的单线程调度执行器。 */
    private final ScheduledExecutorService dispatchExecutor;
    /** 在数据库领取成功后为每个模型任务创建虚拟线程的专用执行器。 */
    private final ExecutorService workerExecutor;
    /** 提供 ready 轮转、参与者 busy 和任务预留的可重建 Redis 投影。 */
    private final PositionAnalysisRedisQueue queue;
    /** 以 MySQL 事务原子领取任务并写入成功或失败终态的状态服务。 */
    private final PositionAnalysisStateService stateService;
    /** 在事务外调用模型，并把结果交回状态服务收口的岗位 Worker。 */
    private final PositionAnalysisWorker worker;
    /** 提供本机并发数和调度失败重试延迟的岗位解析配置。 */
    private final PositionAnalysisProperties properties;
    /** 当前 JVM 的公平本地许可；许可数只限制本进程正在处理的岗位任务。 */
    private final Semaphore permits;
    /** {@code true} 表示当前 JVM 已完成启动恢复并允许新领取，{@code false} 表示尚未开放或已停止。 */
    private final AtomicBoolean running = new AtomicBoolean(false);
    /** {@code true} 表示已有一次领取循环排队或执行，用于合并重复唤醒。 */
    private final AtomicBoolean drainScheduled = new AtomicBoolean(false);
    /** 串行化启动、停止与“Redis 已预留但尚未进入 DB 领取”的边界。 */
    private final Object lifecycleMonitor = new Object();

    /**
     * 装配当前 JVM 调度协作者，并按配置创建公平本地并发许可。
     *
     * @param dispatchExecutor 串行领取和重试执行器
     * @param workerExecutor 岗位模型虚拟线程执行器
     * @param queue Redis 队列投影
     * @param stateService MySQL 任务状态事务服务
     * @param worker 事务外模型 Worker
     * @param properties 岗位解析运行配置
     */
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
            // 把多次事件和 Worker 完成通知合并为单个串行 drain，避免同一 JVM 并发操作领取循环。
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
                    // 本机许可已取得后才在 Redis 原子预留参与者队首，避免无执行容量时提前占用队列。
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
                        // 停止信号先于数据库领取生效时，把尚属 WAITING 的 Redis 预留原样恢复。
                        safelyRestore(reservation);
                        stoppedBeforeStart = true;
                    } else {
                        try {
                            // 以 MySQL 为事实源锁定岗位和当前任务；只有条件更新成功才得到 RUNNING 输入快照。
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
                    // DB 结果未知时恢复预留并延迟重试，不能把 Redis 预留误认为任务已领取。
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
                    // 数据库已明确拒绝旧任务，按原预留身份清理 busy，并允许该参与者后续任务重新进入轮转。
                    safelyFinish(reservation);
                    permits.release();
                    continue;
                }
                if (!Objects.equals(input.queueOwner(), reservation.queueOwner())) {
                    // DB 领取后发现归属与 Redis 预留不一致，将 RUNNING 收口失败；未确认收口时必须保留 busy。
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
                    // 领取已落为 RUNNING 后才创建虚拟线程；提交失败必须先写失败终态再释放 Redis 占用。
                    workerExecutor.execute(() -> runWorker(workerInput, reservation));
                    log.debug("[PositionAnalysis] Worker 已提交: taskId={}, positionId={}, queueOwner={}",
                            workerInput.taskId(), workerInput.positionId(), workerInput.queueOwner());
                } catch (RuntimeException e) {
                    log.error("[PositionAnalysis] 虚拟线程提交失败: taskId={}, errorType={}",
                            workerInput.taskId(), e.getClass().getSimpleName(), e);
                    // 通过源状态条件把本次 RUNNING 收口为稳定失败分类，迟到或旧任务不会覆盖当前事实。
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
                // Worker 在事务外调用 LLM，并仅在成功或失败状态事务得到确定结果时返回 true。
                stateClosed = worker.execute(input);
            } finally {
                try {
                    // 只有数据库终态已确认才释放对应 Redis busy；否则保留占用等待启动恢复。
                    finishIfStateClosed(reservation, stateClosed);
                } finally {
                    // 无论模型和清理路径如何退出，本机许可都只释放一次，并尝试继续本地调度。
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
            // 状态服务只接受仍为当前 RUNNING 的任务；返回的具体归档/过期结果均表示数据库已确定收口。
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
            // 延迟值来自配置且必须为正；到期后仍经 wake 合并重复调度请求。
            dispatchExecutor.schedule(this::wake, delay.toNanos(), TimeUnit.NANOSECONDS);
        } catch (RejectedExecutionException e) {
            if (running.get()) {
                log.warn("[PositionAnalysis] 调度重试被拒绝: errorType={}",
                        e.getClass().getSimpleName());
            }
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

    /**
     * 把 DEBUG 输入改为单行并截为 5000 个 UTF-16 字符，避免无限日志；精确上限依据缺失。
     */
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
