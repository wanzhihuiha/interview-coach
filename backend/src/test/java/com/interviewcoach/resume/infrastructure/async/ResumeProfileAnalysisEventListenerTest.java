package com.interviewcoach.resume.infrastructure.async;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

import com.interviewcoach.resume.application.event.ResumeProfileAnalysisRequestedEvent;
import com.interviewcoach.resume.application.event.ResumeAiTaskLease;
import com.interviewcoach.resume.application.service.ResumeProfileAnalysisWorker;
import com.interviewcoach.resume.infrastructure.redis.ResumeAiQuotaService;
import com.interviewcoach.resume.infrastructure.redis.ResumeAiTaskAdmissionService;
import com.interviewcoach.resume.infrastructure.redis.ResumeAiTaskLeaseRunner;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class ResumeProfileAnalysisEventListenerTest {

    @Test
    void shouldSubmitWorkerToExecutor() {
        ExecutorService executor = Mockito.mock(ExecutorService.class);
        ResumeProfileAnalysisWorker worker = Mockito.mock(ResumeProfileAnalysisWorker.class);
        ResumeAiTaskAdmissionService admissionService = Mockito.mock(ResumeAiTaskAdmissionService.class);
        ResumeAiTaskLeaseRunner leaseRunner = Mockito.mock(ResumeAiTaskLeaseRunner.class);
        ResumeAiQuotaService quotaService = Mockito.mock(ResumeAiQuotaService.class);
        ResumeProfileAnalysisEventListener listener =
                new ResumeProfileAnalysisEventListener(
                        executor, worker, admissionService, leaseRunner, quotaService);
        ResumeProfileAnalysisRequestedEvent event =
                new ResumeProfileAnalysisRequestedEvent(1L, 2L, 3L, "profile-hash");
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

        verify(worker).analyze(event.withTaskLease(lease));
    }

    @Test
    void shouldMarkAnalysisFailedWhenQueueRejectsSubmission() {
        ExecutorService executor = Mockito.mock(ExecutorService.class);
        ResumeProfileAnalysisWorker worker = Mockito.mock(ResumeProfileAnalysisWorker.class);
        ResumeAiTaskAdmissionService admissionService = Mockito.mock(ResumeAiTaskAdmissionService.class);
        ResumeAiTaskLeaseRunner leaseRunner = Mockito.mock(ResumeAiTaskLeaseRunner.class);
        ResumeAiQuotaService quotaService = Mockito.mock(ResumeAiQuotaService.class);
        ResumeProfileAnalysisEventListener listener =
                new ResumeProfileAnalysisEventListener(
                        executor, worker, admissionService, leaseRunner, quotaService);
        ResumeProfileAnalysisRequestedEvent event =
                new ResumeProfileAnalysisRequestedEvent(1L, 2L, 3L, "profile-hash");
        ResumeAiTaskLease lease = new ResumeAiTaskLease("user-permit", "resume-permit");
        Mockito.when(admissionService.acquire(2L, 1L)).thenReturn(lease);
        doThrow(new RejectedExecutionException("queue full"))
                .when(executor).execute(any(Runnable.class));

        listener.onRequested(event);

        verify(leaseRunner).releaseWithoutRun(2L, 1L, lease);
        verify(worker).markSchedulingFailed(event.withTaskLease(lease));
    }

    @Test
    void shouldReleaseAcquiredLeaseWhenExecutorFailsUnexpectedly() {
        ExecutorService executor = Mockito.mock(ExecutorService.class);
        ResumeProfileAnalysisWorker worker = Mockito.mock(ResumeProfileAnalysisWorker.class);
        ResumeAiTaskAdmissionService admissionService = Mockito.mock(ResumeAiTaskAdmissionService.class);
        ResumeAiTaskLeaseRunner leaseRunner = Mockito.mock(ResumeAiTaskLeaseRunner.class);
        ResumeAiQuotaService quotaService = Mockito.mock(ResumeAiQuotaService.class);
        ResumeProfileAnalysisEventListener listener =
                new ResumeProfileAnalysisEventListener(
                        executor, worker, admissionService, leaseRunner, quotaService);
        ResumeProfileAnalysisRequestedEvent event =
                new ResumeProfileAnalysisRequestedEvent(1L, 2L, 3L, "profile-hash");
        ResumeAiTaskLease lease = new ResumeAiTaskLease("user-permit", "resume-permit");
        Mockito.when(admissionService.acquire(2L, 1L)).thenReturn(lease);
        doThrow(new IllegalStateException("executor unavailable"))
                .when(executor).execute(any(Runnable.class));

        listener.onRequested(event);

        verify(leaseRunner).releaseWithoutRun(2L, 1L, lease);
        verify(worker).markSchedulingFailed(event.withTaskLease(lease));
    }

    @Test
    void shouldPropagateSchedulingFailureForRequiredHandoff() {
        ExecutorService executor = Mockito.mock(ExecutorService.class);
        ResumeProfileAnalysisWorker worker = Mockito.mock(ResumeProfileAnalysisWorker.class);
        ResumeAiTaskAdmissionService admissionService = Mockito.mock(ResumeAiTaskAdmissionService.class);
        ResumeAiTaskLeaseRunner leaseRunner = Mockito.mock(ResumeAiTaskLeaseRunner.class);
        ResumeAiQuotaService quotaService = Mockito.mock(ResumeAiQuotaService.class);
        ResumeProfileAnalysisEventListener listener =
                new ResumeProfileAnalysisEventListener(
                        executor, worker, admissionService, leaseRunner, quotaService);
        ResumeAiTaskLease lease = new ResumeAiTaskLease("user-permit", "resume-permit");
        ResumeProfileAnalysisRequestedEvent event = new ResumeProfileAnalysisRequestedEvent(
                1L, 2L, 3L, "profile-hash", lease, null, null, true);
        doThrow(new RejectedExecutionException("executor closed"))
                .when(executor).execute(any(Runnable.class));

        assertThatThrownBy(() -> listener.onRequested(event))
                .isInstanceOf(RejectedExecutionException.class);

        verify(leaseRunner).releaseWithoutRun(2L, 1L, lease);
        verify(worker).markSchedulingFailed(event);
    }
}
