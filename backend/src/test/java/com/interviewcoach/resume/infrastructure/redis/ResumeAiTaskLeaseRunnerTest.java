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

/**
 * 验证 AI 任务租约 Runner 的固定续期调度、丢租约失效与中断，以及 finally 取消续期和释放许可的边界。
 *
 * <p>准入服务和调度器均为 Mock；测试捕获实际续期回调，并用短生命周期虚拟线程观察 Worker 中断。</p>
 */
@ExtendWith(MockitoExtension.class)
class ResumeAiTaskLeaseRunnerTest {

    /** 模拟续期结果和 Worker 结束后的双级许可释放。 */
    @Mock private ResumeAiTaskAdmissionService admissionService;
    /** 模拟按固定频率安排续期回调的平台线程调度器。 */
    @Mock private ScheduledExecutorService renewExecutor;
    /** 模拟已安排的续期任务，供 finally 取消行为断言。 */
    @Mock private ScheduledFuture<Object> renewalFuture;

    /** 保存调度器收到的续期回调，使测试可确定性触发续期失败。 */
    private final AtomicReference<Runnable> renewal = new AtomicReference<>();
    /** 使用一分钟续期间隔和上述 Mock 构造的被测租约 Runner。 */
    private ResumeAiTaskLeaseRunner runner;

    /** 每例重建 Runner，并让调度器 Mock 捕获而不自动执行续期回调。 */
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
