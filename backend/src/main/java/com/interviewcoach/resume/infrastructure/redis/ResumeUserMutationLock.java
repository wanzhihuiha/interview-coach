package com.interviewcoach.resume.infrastructure.redis;

import com.interviewcoach.common.exception.BusinessException;
import com.interviewcoach.resume.application.service.ResumeErrorCode;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * 串行同一用户的简历创建和删除数量变更，不复用 AI 并发许可。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ResumeUserMutationLock {

    private final ObjectProvider<RedissonClient> redissonClientProvider;
    private final ResumeAiTaskProperties properties;

    public <T> T execute(Long userId, Supplier<T> action) {
        return execute(userId, action, false);
    }

    /**
     * 上传使用严格模式：动作完成后若锁已丢失则抛错，由上传编排删除本次数据库记录和文件。
     */
    public <T> T executeChecked(Long userId, Supplier<T> action) {
        return execute(userId, action, true);
    }

    private <T> T execute(Long userId, Supplier<T> action, boolean verifyOwnershipAfterAction) {
        RLock lock;
        try {
            lock = redissonClientProvider.getObject().getLock(
                    properties.getKeyPrefix() + ":mutation:user:" + userId);
            if (!lock.tryLock()) {
                throw new BusinessException(
                        ResumeErrorCode.RESUME_MUTATION_IN_PROGRESS, "当前用户有简历上传或删除正在处理");
            }
        } catch (BusinessException e) {
            throw e;
        } catch (RuntimeException e) {
            throw unavailable(e);
        }
        try {
            T result = action.get();
            if (verifyOwnershipAfterAction && !lock.isHeldByCurrentThread()) {
                throw new BusinessException(
                        ResumeErrorCode.RESUME_TASK_INFRASTRUCTURE_UNAVAILABLE,
                        "简历上传锁已丢失，请稍后重试");
            }
            return result;
        } finally {
            unlockQuietly(lock, userId);
        }
    }

    private void unlockQuietly(RLock lock, Long userId) {
        try {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        } catch (RuntimeException e) {
            log.warn("[ResumeMutation] 用户变更锁释放失败: userId={}, errorType={}",
                    userId, e.getClass().getSimpleName());
        }
    }

    private BusinessException unavailable(RuntimeException cause) {
        log.warn("[ResumeMutation] 用户变更锁不可用: errorType={}", cause.getClass().getSimpleName());
        return new BusinessException(
                ResumeErrorCode.RESUME_TASK_INFRASTRUCTURE_UNAVAILABLE,
                "简历任务基础设施暂不可用，请稍后重试",
                cause);
    }
}
