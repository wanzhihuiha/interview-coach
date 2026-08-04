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

    public static final String RENEW_EXECUTOR_BEAN_NAME = "resumeAiTaskLeaseRenewExecutor";

    private final ResumeAiTaskAdmissionService admissionService;
    private final ResumeAiTaskProperties properties;
    private final ScheduledExecutorService renewExecutor;

    public ResumeAiTaskLeaseRunner(
            ResumeAiTaskAdmissionService admissionService,
            ResumeAiTaskProperties properties,
            @Qualifier(RENEW_EXECUTOR_BEAN_NAME) ScheduledExecutorService renewExecutor) {
        this.admissionService = admissionService;
        this.properties = properties;
        this.renewExecutor = renewExecutor;
    }

    public void run(Long userId, Long resumeId, ResumeAiTaskLease lease, Runnable worker) {
        Thread workerThread = Thread.currentThread();
        ScheduledFuture<?> renewal = null;
        try {
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
            admissionService.release(userId, resumeId, lease);
        }
    }

    public void releaseWithoutRun(Long userId, Long resumeId, ResumeAiTaskLease lease) {
        admissionService.release(userId, resumeId, lease);
    }

    private void renew(Long userId, Long resumeId, ResumeAiTaskLease lease, Thread workerThread) {
        if (!admissionService.renew(userId, resumeId, lease) && lease.markLost()) {
            log.warn("[ResumeTaskAdmission] 任务租约已丢失并中断 Worker: userId={}, resumeId={}",
                    userId, resumeId);
            workerThread.interrupt();
        }
    }
}
