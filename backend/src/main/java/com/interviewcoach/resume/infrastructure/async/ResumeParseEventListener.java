package com.interviewcoach.resume.infrastructure.async;

import com.interviewcoach.resume.application.event.ResumeParseRequestedEvent;
import com.interviewcoach.resume.application.service.ResumeParseWorker;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 在简历状态事务提交后，将解析事件送入进程内专用线程池。
 *
 * <p>队列不提供跨进程持久化；队列拒绝时会立即把仍可处理的任务标记为失败。</p>
 */
@Slf4j
@Component
public class ResumeParseEventListener {

    private final ExecutorService executor;
    private final ResumeParseWorker worker;

    public ResumeParseEventListener(
            @Qualifier(ResumeParseExecutorConfig.EXECUTOR_BEAN_NAME) ExecutorService executor,
            ResumeParseWorker worker) {
        this.executor = executor;
        this.worker = worker;
    }

    /**
     * 事务提交后只负责入队，使上传和重新解析接口能够立即返回。
     * 若有界队列已满，则记录调度失败并同步更新失败状态。
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onRequested(ResumeParseRequestedEvent event) {
        log.info("[ResumeParse] 已接收事务提交后的解析事件: resumeId={}, forceRefresh={}",
                event.resumeId(), event.forceRefresh());
        try {
            executor.execute(() -> worker.parse(event));
            log.info("[ResumeParse] 解析任务已提交线程池: resumeId={}", event.resumeId());
        } catch (RejectedExecutionException e) {
            log.error("[ResumeParse] 解析任务调度失败: resumeId={}, stage=SCHEDULING, errorType={}",
                    event.resumeId(), e.getClass().getSimpleName(), e);
            worker.markSchedulingFailed(event);
        }
    }
}
