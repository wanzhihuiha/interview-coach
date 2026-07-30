package com.interviewcoach.resume.infrastructure.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.interviewcoach.common.exception.BusinessException;
import com.interviewcoach.resume.application.event.ResumeAiTaskLease;
import com.interviewcoach.resume.application.service.ResumeErrorCode;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RPermitExpirableSemaphore;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;

@ExtendWith(MockitoExtension.class)
class ResumeAiTaskAdmissionServiceTest {

    @Mock private ObjectProvider<RedissonClient> clientProvider;
    @Mock private RedissonClient redissonClient;
    @Mock private RPermitExpirableSemaphore userSemaphore;
    @Mock private RPermitExpirableSemaphore resumeSemaphore;

    private ResumeAiTaskAdmissionService service;

    @BeforeEach
    void setUp() {
        ResumeAiTaskProperties properties = new ResumeAiTaskProperties();
        properties.setAcquireWait(Duration.ZERO);
        properties.setLease(Duration.ofMinutes(5));
        service = new ResumeAiTaskAdmissionService(clientProvider, properties);
        when(clientProvider.getObject()).thenReturn(redissonClient);
        when(redissonClient.getPermitExpirableSemaphore(service.userKey(2L)))
                .thenReturn(userSemaphore);
        when(redissonClient.getPermitExpirableSemaphore(service.resumeKey(2L, 1L)))
                .thenReturn(resumeSemaphore);
    }

    @Test
    void shouldAcquireUserThenResumePermit() throws Exception {
        when(userSemaphore.tryAcquire(0L, 300000L, TimeUnit.MILLISECONDS)).thenReturn("user-permit");
        when(resumeSemaphore.tryAcquire(0L, 300000L, TimeUnit.MILLISECONDS)).thenReturn("resume-permit");

        ResumeAiTaskLease lease = service.acquire(2L, 1L);

        assertThat(lease.userPermitId()).isEqualTo("user-permit");
        assertThat(lease.resumePermitId()).isEqualTo("resume-permit");
        InOrder order = inOrder(userSemaphore, resumeSemaphore);
        order.verify(userSemaphore).tryAcquire(0L, 300000L, TimeUnit.MILLISECONDS);
        order.verify(resumeSemaphore).tryAcquire(0L, 300000L, TimeUnit.MILLISECONDS);
    }

    @Test
    void shouldReleaseUserPermitWhenResumePermitUnavailable() throws Exception {
        when(userSemaphore.tryAcquire(0L, 300000L, TimeUnit.MILLISECONDS)).thenReturn("user-permit");
        when(resumeSemaphore.tryAcquire(0L, 300000L, TimeUnit.MILLISECONDS)).thenReturn(null);

        assertThatThrownBy(() -> service.acquire(2L, 1L))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getCode())
                        .isEqualTo(ResumeErrorCode.RESUME_AI_CONCURRENCY_LIMIT));
        verify(userSemaphore).tryRelease("user-permit");
    }

    @Test
    void shouldRejectUserLimitBeforeTryingResumePermit() throws Exception {
        when(userSemaphore.tryAcquire(0L, 300000L, TimeUnit.MILLISECONDS)).thenReturn(null);

        assertThatThrownBy(() -> service.acquire(2L, 1L))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getCode())
                        .isEqualTo(ResumeErrorCode.USER_AI_CONCURRENCY_LIMIT));

        verify(resumeSemaphore, never())
                .tryAcquire(0L, 300000L, TimeUnit.MILLISECONDS);
    }

    @Test
    void shouldReleaseInReverseOrder() {
        ResumeAiTaskLease lease = new ResumeAiTaskLease("user-permit", "resume-permit");

        service.release(2L, 1L, lease);

        InOrder order = inOrder(resumeSemaphore, userSemaphore);
        order.verify(resumeSemaphore).tryRelease("resume-permit");
        order.verify(userSemaphore).tryRelease("user-permit");
    }

    @Test
    void shouldLoseWholeLeaseWhenEitherRenewalFails() {
        ResumeAiTaskLease lease = new ResumeAiTaskLease("user-permit", "resume-permit");
        when(resumeSemaphore.updateLeaseTime("resume-permit", 300000L, TimeUnit.MILLISECONDS))
                .thenReturn(true);
        when(userSemaphore.updateLeaseTime("user-permit", 300000L, TimeUnit.MILLISECONDS))
                .thenReturn(false);

        assertThat(service.renew(2L, 1L, lease)).isFalse();
    }
}
