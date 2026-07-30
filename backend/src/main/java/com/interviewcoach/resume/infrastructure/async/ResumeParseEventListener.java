package com.interviewcoach.resume.infrastructure.async;

import com.interviewcoach.resume.application.event.ResumeParseRequestedEvent;
import com.interviewcoach.resume.application.service.ResumeParseWorker;
import com.interviewcoach.resume.infrastructure.redis.ResumeAiQuotaService;
import com.interviewcoach.resume.infrastructure.redis.ResumeAiTaskAdmissionService;
import com.interviewcoach.resume.infrastructure.redis.ResumeAiTaskLeaseRunner;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 在状态事务提交后完成分布式准入，再把解析事件送入虚拟线程。
 */
@Slf4j
@Component
public class ResumeParseEventListener {

    private final ExecutorService executor;
    private final ResumeParseWorker worker;
    private final ResumeAiTaskAdmissionService admissionService;
    private final ResumeAiTaskLeaseRunner leaseRunner;

    @Autowired
    public ResumeParseEventListener(
            @Qualifier(ResumeParseExecutorConfig.EXECUTOR_BEAN_NAME) ExecutorService executor,
            ResumeParseWorker worker,
            ResumeAiTaskAdmissionService admissionService,
            ResumeAiTaskLeaseRunner leaseRunner) {
        this.executor = executor;
        this.worker = worker;
        this.admissionService = admissionService;
        this.leaseRunner = leaseRunner;
    }

    /**
     * 兼容现有手工构造调用；quota 结算已经由 Worker 按数据库终态处理。
     */
    public ResumeParseEventListener(
            ExecutorService executor,
            ResumeParseWorker worker,
            ResumeAiTaskAdmissionService admissionService,
            ResumeAiTaskLeaseRunner leaseRunner,
            ResumeAiQuotaService ignoredQuotaService) {
        this(executor, worker, admissionService, leaseRunner);
    }

    /**
     * 事务提交后非阻塞取得许可；许可不可得或执行器关闭时不进入后台任务。
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onRequested(ResumeParseRequestedEvent event) {
        log.info("[ResumeParse] 已接收事务提交后的解析事件: resumeId={}, forceRefresh={}",
                event.resumeId(), event.forceRefresh());
        boolean requiredPreAdmittedTask = event.taskLease() != null;
        ResumeParseRequestedEvent admittedEvent = event;
        try {
            if (event.taskLease() == null) {
                admittedEvent = event.withTaskLease(
                        admissionService.acquire(event.userId(), event.resumeId()));
            }
            ResumeParseRequestedEvent taskEvent = admittedEvent;
            executor.execute(() -> runWorker(taskEvent));
            log.info("[ResumeParse] 解析任务已提交虚拟线程: resumeId={}", event.resumeId());
        } catch (RejectedExecutionException e) {
            log.error("[ResumeParse] 解析任务调度失败: resumeId={}, stage=SCHEDULING, errorType={}",
                    event.resumeId(), e.getClass().getSimpleName(), e);
            releaseLeaseAfterSubmissionFailure(admittedEvent);
            worker.markSchedulingFailed(admittedEvent);
            if (requiredPreAdmittedTask) {
                throw e;
            }
        } catch (RuntimeException e) {
            if (admittedEvent.taskLease() != null) {
                log.warn("[ResumeParse] 解析任务提交失败: resumeId={}, errorType={}",
                        event.resumeId(), e.getClass().getSimpleName());
                releaseLeaseAfterSubmissionFailure(admittedEvent);
                worker.markSchedulingFailed(admittedEvent);
                if (requiredPreAdmittedTask) {
                    throw e;
                }
            } else {
                log.warn("[ResumeParse] 解析任务准入失败: resumeId={}, errorType={}",
                        event.resumeId(), e.getClass().getSimpleName());
                worker.markAdmissionFailed(event);
            }
        }
    }

    private void runWorker(ResumeParseRequestedEvent event) {
        try {
            leaseRunner.run(
                    event.userId(), event.resumeId(), event.taskLease(), () -> worker.parse(event));
        } catch (RuntimeException e) {
            log.error("[ResumeParse] 许可续期调度失败: resumeId={}, errorType={}",
                    event.resumeId(), e.getClass().getSimpleName(), e);
            worker.markSchedulingFailed(event);
        }
    }

    private void releaseLeaseAfterSubmissionFailure(ResumeParseRequestedEvent event) {
        if (event.taskLease() != null) {
            try {
                leaseRunner.releaseWithoutRun(event.userId(), event.resumeId(), event.taskLease());
            } catch (RuntimeException e) {
                log.warn(
                        "[ResumeParse] 调度失败后的许可释放失败，继续确认数据库终态: "
                                + "resumeId={}, errorType={}",
                        event.resumeId(),
                        e.getClass().getSimpleName());
            }
        }
    }
}
