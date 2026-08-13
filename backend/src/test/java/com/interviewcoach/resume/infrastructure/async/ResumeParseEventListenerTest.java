package com.interviewcoach.resume.infrastructure.async;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

import com.interviewcoach.resume.application.event.ResumeParseRequestedEvent;
import com.interviewcoach.resume.application.event.ResumeAiTaskLease;
import com.interviewcoach.resume.application.event.ResumeAiQuotaReservation;
import com.interviewcoach.resume.application.service.ResumeParseWorker;
import com.interviewcoach.resume.infrastructure.redis.ResumeAiQuotaService;
import com.interviewcoach.resume.infrastructure.redis.ResumeAiTaskAdmissionService;
import com.interviewcoach.resume.infrastructure.redis.ResumeAiTaskLeaseRunner;
import java.time.LocalDate;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

/**
 * 验证简历解析事件监听器的许可准入、执行器提交、租约包装和拒绝补偿边界。
 *
 * <p>执行器与全部协作者均为 Mock；用例区分监听器自行准入的可选交接和上传流程已携带租约的必需交接。</p>
 */
class ResumeParseEventListenerTest {

    @Test
    void shouldSubmitWorkerToExecutor() {
        ExecutorService executor = Mockito.mock(ExecutorService.class);
        ResumeParseWorker worker = Mockito.mock(ResumeParseWorker.class);
        ResumeAiTaskAdmissionService admissionService = Mockito.mock(ResumeAiTaskAdmissionService.class);
        ResumeAiTaskLeaseRunner leaseRunner = Mockito.mock(ResumeAiTaskLeaseRunner.class);
        ResumeAiQuotaService quotaService = Mockito.mock(ResumeAiQuotaService.class);
        ResumeParseEventListener listener = new ResumeParseEventListener(
                executor, worker, admissionService, leaseRunner, quotaService);
        ResumeParseRequestedEvent event = new ResumeParseRequestedEvent(1L, 2L, 3L, false);
        ResumeAiTaskLease lease = new ResumeAiTaskLease("user-permit", "resume-permit");
        Mockito.when(admissionService.acquire(2L, 1L)).thenReturn(lease);
        Mockito.doAnswer(invocation -> {
                    ((Runnable) invocation.getArgument(3)).run();
                    return null;
                })
                .when(leaseRunner).run(Mockito.eq(2L), Mockito.eq(1L), Mockito.eq(lease), any(Runnable.class));
        ArgumentCaptor<Runnable> taskCaptor = ArgumentCaptor.forClass(Runnable.class);

        listener.onRequested(event);
        verify(executor).execute(taskCaptor.capture());
        taskCaptor.getValue().run();

        verify(worker).parse(event.withTaskLease(lease));
    }

    @Test
    void shouldMarkTaskFailedWhenQueueRejectsSubmission() {
        ExecutorService executor = Mockito.mock(ExecutorService.class);
        ResumeParseWorker worker = Mockito.mock(ResumeParseWorker.class);
        ResumeAiTaskAdmissionService admissionService = Mockito.mock(ResumeAiTaskAdmissionService.class);
        ResumeAiTaskLeaseRunner leaseRunner = Mockito.mock(ResumeAiTaskLeaseRunner.class);
        ResumeAiQuotaService quotaService = Mockito.mock(ResumeAiQuotaService.class);
        ResumeParseEventListener listener = new ResumeParseEventListener(
                executor, worker, admissionService, leaseRunner, quotaService);
        ResumeAiQuotaReservation quota =
                new ResumeAiQuotaReservation(LocalDate.of(2026, 7, 30), "quota-token");
        ResumeParseRequestedEvent event =
                new ResumeParseRequestedEvent(1L, 2L, 3L, false, null, quota);
        ResumeAiTaskLease lease = new ResumeAiTaskLease("user-permit", "resume-permit");
        Mockito.when(admissionService.acquire(2L, 1L)).thenReturn(lease);
        doThrow(new RejectedExecutionException("queue full")).when(executor).execute(any(Runnable.class));

        listener.onRequested(event);

        verify(leaseRunner).releaseWithoutRun(2L, 1L, lease);
        verify(quotaService).markFailed(2L, quota);
        verify(worker).markSchedulingFailed(event.withTaskLease(lease));
    }

    @Test
    void shouldPropagateSubmissionFailureForRequiredPreAdmittedUpload() {
        ExecutorService executor = Mockito.mock(ExecutorService.class);
        ResumeParseWorker worker = Mockito.mock(ResumeParseWorker.class);
        ResumeAiTaskAdmissionService admissionService = Mockito.mock(ResumeAiTaskAdmissionService.class);
        ResumeAiTaskLeaseRunner leaseRunner = Mockito.mock(ResumeAiTaskLeaseRunner.class);
        ResumeAiQuotaService quotaService = Mockito.mock(ResumeAiQuotaService.class);
        ResumeParseEventListener listener = new ResumeParseEventListener(
                executor, worker, admissionService, leaseRunner, quotaService);
        ResumeAiTaskLease lease = new ResumeAiTaskLease("user-permit", "resume-permit");
        ResumeParseRequestedEvent event =
                new ResumeParseRequestedEvent(1L, 2L, 3L, false, lease, null);
        doThrow(new RejectedExecutionException("executor closed"))
                .when(executor).execute(any(Runnable.class));

        assertThatThrownBy(() -> listener.onRequested(event))
                .isInstanceOf(RejectedExecutionException.class);

        verify(leaseRunner).releaseWithoutRun(2L, 1L, lease);
        verify(worker).markSchedulingFailed(event);
    }
}
