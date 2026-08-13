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
 * 有活动事务时在正式画像提交后完成 Redisson 许可准入，再将辅助分析提交到虚拟线程；
 * 无事务发布会因 {@code fallbackExecution=true} 在发布线程立即进入同一处理逻辑。
 */
@Slf4j
@Component
public class ResumeProfileAnalysisEventListener {

    /** 接收已准入辅助分析任务的逐任务虚拟线程执行器。 */
    private final ExecutorService executor;
    /** 准备模型输入、调用 Agent 并按任务守卫写回终态的 Worker。 */
    private final ResumeProfileAnalysisWorker worker;
    /** 按用户、简历顺序取得分布式许可的准入服务。 */
    private final ResumeAiTaskAdmissionService admissionService;
    /** 在 Worker 运行期间续期许可并在结束时释放的执行包装器。 */
    private final ResumeAiTaskLeaseRunner leaseRunner;

    /** 注入简历任务专用执行器及准入、续期和 Worker 协作者。 */
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
     * 有事务时在提交后、无事务时立即取得用户级和简历级许可，失败时不进入后台执行器。
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onRequested(ResumeProfileAnalysisRequestedEvent event) {
        ResumeProfileAnalysisRequestedEvent admittedEvent = event;
        boolean requiredHandoff = event.requiredHandoff() && event.taskLease() != null;
        try {
            if (event.taskLease() == null) {
                // 兼容非预准入事件：按用户级、简历级顺序取得许可，失败时不提交后台任务。
                admittedEvent = event.withTaskLease(
                        admissionService.acquire(event.userId(), event.resumeId()));
            }
            ResumeProfileAnalysisRequestedEvent taskEvent = admittedEvent;
            // 将带租约且可能含敏感反馈的事件交给虚拟线程；反馈不写入日志或数据库。
            executor.execute(() -> runWorker(taskEvent));
        } catch (RejectedExecutionException e) {
            log.warn("[ResumeProfileAnalysis] 分析任务调度失败: resumeId={}", event.resumeId());
            // 调度拒绝时先尽力释放许可，再按任务代次写回失败；必需交接事件继续抛给提交方补偿。
            releaseLeaseAfterSubmissionFailure(admittedEvent);
            worker.markSchedulingFailed(admittedEvent);
            if (requiredHandoff) {
                throw e;
            }
        } catch (RuntimeException e) {
            if (admittedEvent.taskLease() != null) {
                log.warn("[ResumeProfileAnalysis] 分析任务提交失败: resumeId={}, errorType={}",
                        event.resumeId(), e.getClass().getSimpleName());
                // 已准入但提交失败，执行许可释放和数据库状态补偿。
                releaseLeaseAfterSubmissionFailure(admittedEvent);
                worker.markSchedulingFailed(admittedEvent);
                if (requiredHandoff) {
                    throw e;
                }
            } else {
                log.warn("[ResumeProfileAnalysis] 分析任务准入失败: resumeId={}, errorType={}",
                        event.resumeId(), e.getClass().getSimpleName());
                // 准入失败时没有许可可释放，仅将当前任务写为基础设施失败。
                worker.markAdmissionFailed(event);
            }
        }
    }

    /** 在许可续期包装内运行辅助分析 Worker；续期调度异常时按调度失败尝试写回。 */
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

    /** 任务未开始时逆序释放简历级和用户级许可；释放失败只记录安全摘要。 */
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
