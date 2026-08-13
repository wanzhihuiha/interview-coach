package com.interviewcoach.resume.infrastructure.redis;

import com.interviewcoach.resume.application.event.ResumeAiTaskLease;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * 在 Worker 所在线程执行期间续期许可，并在所有结束路径按 permit id 释放。
 */
@Slf4j
@Component
public class ResumeAiTaskLeaseRunner {

    /** 许可续期使用的单个平台调度线程 Bean 名称。 */
    public static final String RENEW_EXECUTOR_BEAN_NAME = "resumeAiTaskLeaseRenewExecutor";

    /** 执行两个许可的续期与逆序释放。 */
    private final ResumeAiTaskAdmissionService admissionService;
    /** 提供续期间隔和租期配置。 */
    private final ResumeAiTaskProperties properties;
    /** 只负责定期续期的调度器，不承载 Worker。 */
    private final ScheduledExecutorService renewExecutor;

    /** 注入准入服务、任务配置和专用续期调度器。 */
    public ResumeAiTaskLeaseRunner(
            ResumeAiTaskAdmissionService admissionService,
            ResumeAiTaskProperties properties,
            @Qualifier(RENEW_EXECUTOR_BEAN_NAME) ScheduledExecutorService renewExecutor) {
        this.admissionService = admissionService;
        this.properties = properties;
        this.renewExecutor = renewExecutor;
    }

    /** 在当前 Worker 线程运行任务，同时按配置频率续期；任何结束路径都会取消续期并释放许可。 */
    public void run(Long userId, Long resumeId, ResumeAiTaskLease lease, Runnable worker) {
        Thread workerThread = Thread.currentThread();
        ScheduledFuture<?> renewal = null;
        try {
            // 定期同时续期两个许可；首次执行在一个完整续期间隔之后。
            renewal = renewExecutor.scheduleAtFixedRate(
                    () -> renew(userId, resumeId, lease, workerThread),
                    properties.getRenewInterval().toMillis(),
                    properties.getRenewInterval().toMillis(),
                    TimeUnit.MILLISECONDS);
            worker.run();
        } finally {
            if (renewal != null) {
                renewal.cancel(false);
            }
            // Worker 成功、失败或被中断均逆序尽力释放两个许可。
            admissionService.release(userId, resumeId, lease);
        }
    }

    /** 后台任务尚未开始时直接释放已取得的两个许可。 */
    public void releaseWithoutRun(Long userId, Long resumeId, ResumeAiTaskLease lease) {
        admissionService.release(userId, resumeId, lease);
    }

    /** 任一许可续期失败时单向标记租约丢失并中断 Worker，阻止其通过租约守卫写回成功。 */
    private void renew(Long userId, Long resumeId, ResumeAiTaskLease lease, Thread workerThread) {
        if (!admissionService.renew(userId, resumeId, lease) && lease.markLost()) {
            log.warn("[ResumeTaskAdmission] 任务租约已丢失并中断 Worker: userId={}, resumeId={}",
                    userId, resumeId);
            workerThread.interrupt();
        }
    }
}
