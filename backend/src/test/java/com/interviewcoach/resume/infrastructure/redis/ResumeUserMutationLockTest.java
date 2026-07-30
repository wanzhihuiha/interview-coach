package com.interviewcoach.resume.infrastructure.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.interviewcoach.common.exception.BusinessException;
import com.interviewcoach.resume.application.service.ResumeErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;

@ExtendWith(MockitoExtension.class)
class ResumeUserMutationLockTest {

    @Mock private ObjectProvider<RedissonClient> clientProvider;
    @Mock private RedissonClient redissonClient;
    @Mock private RLock lock;

    private ResumeUserMutationLock mutationLock;

    @BeforeEach
    void setUp() {
        mutationLock = new ResumeUserMutationLock(clientProvider, new ResumeAiTaskProperties());
        when(clientProvider.getObject()).thenReturn(redissonClient);
        when(redissonClient.getLock("interview-coach:resume-ai:v1:mutation:user:3"))
                .thenReturn(lock);
    }

    @Test
    void shouldExecuteAndUnlockOnCurrentThread() {
        when(lock.tryLock()).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);

        String result = mutationLock.execute(3L, () -> "created");

        assertThat(result).isEqualTo("created");
        verify(lock).unlock();
    }

    @Test
    void shouldRejectConcurrentMutationWithoutWaiting() {
        when(lock.tryLock()).thenReturn(false);

        assertThatThrownBy(() -> mutationLock.execute(3L, () -> "never"))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getCode())
                        .isEqualTo(ResumeErrorCode.RESUME_MUTATION_IN_PROGRESS));
    }

    @Test
    void shouldFailCheckedActionWhenLockWasLost() {
        when(lock.tryLock()).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(false);

        assertThatThrownBy(() -> mutationLock.executeChecked(3L, () -> "created"))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getCode())
                        .isEqualTo(ResumeErrorCode.RESUME_TASK_INFRASTRUCTURE_UNAVAILABLE));
    }
}
