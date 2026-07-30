package com.interviewcoach.resume.infrastructure.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.interviewcoach.resume.application.event.ResumeAiTaskLease;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ResumeAiTaskLeaseRunnerTest {

    @Mock private ResumeAiTaskAdmissionService admissionService;
    @Mock private ScheduledExecutorService renewExecutor;
    @Mock private ScheduledFuture<Object> renewalFuture;

    private final AtomicReference<Runnable> renewal = new AtomicReference<>();
    private ResumeAiTaskLeaseRunner runner;

    @BeforeEach
    void setUp() {
        ResumeAiTaskProperties properties = new ResumeAiTaskProperties();
        properties.setRenewInterval(Duration.ofMinutes(1));
        runner = new ResumeAiTaskLeaseRunner(admissionService, properties, renewExecutor);
        when(renewExecutor.scheduleAtFixedRate(
                        any(Runnable.class), anyLong(), anyLong(), eq(TimeUnit.MILLISECONDS)))
                .thenAnswer(invocation -> {
                    renewal.set(invocation.getArgument(0));
                    return renewalFuture;
                });
    }

    @Test
    void shouldCancelRenewalAndReleaseInFinally() {
        ResumeAiTaskLease lease = new ResumeAiTaskLease("user-permit", "resume-permit");
        AtomicBoolean executed = new AtomicBoolean();

        runner.run(2L, 1L, lease, () -> executed.set(true));

        assertThat(executed).isTrue();
        verify(renewalFuture).cancel(false);
        verify(admissionService).release(2L, 1L, lease);
    }

    @Test
    void shouldInvalidateLeaseAndInterruptWorkerWhenRenewalFails() throws Exception {
        ResumeAiTaskLease lease = new ResumeAiTaskLease("user-permit", "resume-permit");
        CountDownLatch workerStarted = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean();
        when(admissionService.renew(2L, 1L, lease)).thenReturn(false);
        Thread workerThread = Thread.ofVirtual().start(() -> runner.run(2L, 1L, lease, () -> {
            workerStarted.countDown();
            try {
                Thread.sleep(5000L);
            } catch (InterruptedException e) {
                interrupted.set(true);
                Thread.currentThread().interrupt();
            }
        }));

        assertThat(workerStarted.await(1, TimeUnit.SECONDS)).isTrue();
        renewal.get().run();
        workerThread.join(1000L);

        assertThat(lease.isValid()).isFalse();
        assertThat(interrupted).isTrue();
        assertThat(workerThread.isAlive()).isFalse();
        verify(admissionService).release(2L, 1L, lease);
    }
}
