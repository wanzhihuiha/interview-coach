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

/**
 * 验证用户级简历变更锁的默认无等待获取、当前线程释放和严格模式丢锁拒绝边界。
 *
 * <p>RedissonClient 与锁均为 Mock；用例只固定 Key 归属及调用结果，不验证多实例是否共享同一 Redis Key 空间。</p>
 */
@ExtendWith(MockitoExtension.class)
class ResumeUserMutationLockTest {

    /** 模拟延迟获取 RedissonClient 的 Spring Provider。 */
    @Mock private ObjectProvider<RedissonClient> clientProvider;
    /** 模拟按用户范围 Key 获取锁的 Redisson 客户端。 */
    @Mock private RedissonClient redissonClient;
    /** 模拟当前用户变更锁的获取、归属检查和释放。 */
    @Mock private RLock lock;

    /** 使用默认锁配置与上述 Mock 构造的被测用户变更锁。 */
    private ResumeUserMutationLock mutationLock;

    /** 每例重建被测对象，并将用户 3 的固定 Key 绑定到锁 Mock。 */
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
