package com.interviewcoach.resume.infrastructure.async;

import com.interviewcoach.resume.application.event.ResumeProfileAnalysisRequestedEvent;
import com.interviewcoach.resume.application.service.ResumeProfileAnalysisWorker;
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
 * 在正式画像事务提交后完成分布式准入，再将辅助分析提交到虚拟线程。
 */
@Slf4j
@Component
public class ResumeProfileAnalysisEventListener {

    private final ExecutorService executor;
    private final ResumeProfileAnalysisWorker worker;
    private final ResumeAiTaskAdmissionService admissionService;
    private final ResumeAiTaskLeaseRunner leaseRunner;

    @Autowired
    public ResumeProfileAnalysisEventListener(
            @Qualifier(ResumeParseExecutorConfig.EXECUTOR_BEAN_NAME) ExecutorService executor,
            ResumeProfileAnalysisWorker worker,
            ResumeAiTaskAdmissionService admissionService,
            ResumeAiTaskLeaseRunner leaseRunner) {
        this.executor = executor;
        this.worker = worker;
        this.admissionService = admissionService;
        this.leaseRunner = leaseRunner;
    }

    /**
     * 保留现有直接构造调用兼容；quota 的数据库确认与结算已统一由 Worker 负责。
     */
    public ResumeProfileAnalysisEventListener(
            ExecutorService executor,
            ResumeProfileAnalysisWorker worker,
            ResumeAiTaskAdmissionService admissionService,
            ResumeAiTaskLeaseRunner leaseRunner,
            ResumeAiQuotaService ignoredQuotaService) {
        this(executor, worker, admissionService, leaseRunner);
    }

    /**
     * 提交后的事件先取得用户级和简历级许可，失败时不进入后台执行器。
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onRequested(ResumeProfileAnalysisRequestedEvent event) {
        ResumeProfileAnalysisRequestedEvent admittedEvent = event;
        boolean requiredHandoff = event.requiredHandoff() && event.taskLease() != null;
        try {
            if (event.taskLease() == null) {
                admittedEvent = event.withTaskLease(
                        admissionService.acquire(event.userId(), event.resumeId()));
            }
            ResumeProfileAnalysisRequestedEvent taskEvent = admittedEvent;
            executor.execute(() -> runWorker(taskEvent));
        } catch (RejectedExecutionException e) {
            log.warn("[ResumeProfileAnalysis] 分析任务调度失败: resumeId={}", event.resumeId());
            releaseLeaseAfterSubmissionFailure(admittedEvent);
            worker.markSchedulingFailed(admittedEvent);
            if (requiredHandoff) {
                throw e;
            }
        } catch (RuntimeException e) {
            if (admittedEvent.taskLease() != null) {
                log.warn("[ResumeProfileAnalysis] 分析任务提交失败: resumeId={}, errorType={}",
                        event.resumeId(), e.getClass().getSimpleName());
                releaseLeaseAfterSubmissionFailure(admittedEvent);
                worker.markSchedulingFailed(admittedEvent);
                if (requiredHandoff) {
                    throw e;
                }
            } else {
                log.warn("[ResumeProfileAnalysis] 分析任务准入失败: resumeId={}, errorType={}",
                        event.resumeId(), e.getClass().getSimpleName());
                worker.markAdmissionFailed(event);
            }
        }
    }

    private void runWorker(ResumeProfileAnalysisRequestedEvent event) {
        try {
            leaseRunner.run(
                    event.userId(), event.resumeId(), event.taskLease(), () -> worker.analyze(event));
        } catch (RuntimeException e) {
            log.error("[ResumeProfileAnalysis] 许可续期调度失败: resumeId={}, errorType={}",
                    event.resumeId(), e.getClass().getSimpleName(), e);
            worker.markSchedulingFailed(event);
        }
    }

    private void releaseLeaseAfterSubmissionFailure(ResumeProfileAnalysisRequestedEvent event) {
        if (event.taskLease() == null) {
            return;
        }
        try {
            leaseRunner.releaseWithoutRun(event.userId(), event.resumeId(), event.taskLease());
        } catch (RuntimeException releaseError) {
            log.warn(
                    "[ResumeProfileAnalysis] 调度失败后的许可释放失败: resumeId={}, errorType={}",
                    event.resumeId(),
                    releaseError.getClass().getSimpleName());
        }
    }
}
