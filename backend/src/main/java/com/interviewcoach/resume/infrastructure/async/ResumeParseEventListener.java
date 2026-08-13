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
 * 有活动事务时在状态提交后完成 Redisson 许可准入，再把解析事件送入虚拟线程；
 * 无事务发布会因 {@code fallbackExecution=true} 在发布线程立即进入同一处理逻辑。
 */
@Slf4j
@Component
public class ResumeParseEventListener {

    /** 接收已准入任务的逐任务虚拟线程执行器。 */
    private final ExecutorService executor;
    /** 执行当前代次事实解析并写回数据库终态的 Worker。 */
    private final ResumeParseWorker worker;
    /** 按用户、简历顺序取得分布式许可的准入服务。 */
    private final ResumeAiTaskAdmissionService admissionService;
    /** 在 Worker 运行期间续期许可并在结束时释放的执行包装器。 */
    private final ResumeAiTaskLeaseRunner leaseRunner;

    /** 注入简历任务专用执行器及准入、续期和 Worker 协作者。 */
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
     * 有事务时在提交后、无事务时立即按配置的每级等待时长取得许可；许可不可得或执行器关闭时不进入后台任务。
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onRequested(ResumeParseRequestedEvent event) {
        log.info("[ResumeParse] 已接收事务提交后的解析事件: resumeId={}, forceRefresh={}",
                event.resumeId(), event.forceRefresh());
        boolean requiredPreAdmittedTask = event.taskLease() != null;
        ResumeParseRequestedEvent admittedEvent = event;
        try {
            if (event.taskLease() == null) {
                // 兼容未预准入事件：按用户级、简历级顺序取得许可，任何失败都不提交后台任务。
                admittedEvent = event.withTaskLease(
                        admissionService.acquire(event.userId(), event.resumeId()));
            }
            ResumeParseRequestedEvent taskEvent = admittedEvent;
            // 把带租约的不可变事件交给虚拟线程；执行器拒绝时进入状态写回和许可补偿。
            executor.execute(() -> runWorker(taskEvent));
            log.info("[ResumeParse] 解析任务已提交虚拟线程: resumeId={}", event.resumeId());
        } catch (RejectedExecutionException e) {
            log.error("[ResumeParse] 解析任务调度失败: resumeId={}, stage=SCHEDULING, errorType={}",
                    event.resumeId(), e.getClass().getSimpleName(), e);
            // 调度未成功时先尽力释放已取得许可，再由 Worker 根据数据库任务代次写回失败。
            releaseLeaseAfterSubmissionFailure(admittedEvent);
            worker.markSchedulingFailed(admittedEvent);
            if (requiredPreAdmittedTask) {
                throw e;
            }
        } catch (RuntimeException e) {
            if (admittedEvent.taskLease() != null) {
                log.warn("[ResumeParse] 解析任务提交失败: resumeId={}, errorType={}",
                        event.resumeId(), e.getClass().getSimpleName());
                // 已准入但提交失败，执行与拒绝分支相同的许可和数据库补偿。
                releaseLeaseAfterSubmissionFailure(admittedEvent);
                worker.markSchedulingFailed(admittedEvent);
                if (requiredPreAdmittedTask) {
                    throw e;
                }
            } else {
                log.warn("[ResumeParse] 解析任务准入失败: resumeId={}, errorType={}",
                        event.resumeId(), e.getClass().getSimpleName());
                // 准入本身失败时没有许可可释放，只按当前代次记录基础设施失败。
                worker.markAdmissionFailed(event);
            }
        }
    }

    /** 在许可续期包装内运行 Worker；续期调度异常时按调度失败尝试写回。 */
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

    /** 任务未开始时逆序释放简历级和用户级许可；释放失败不跳过数据库终态确认。 */
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
