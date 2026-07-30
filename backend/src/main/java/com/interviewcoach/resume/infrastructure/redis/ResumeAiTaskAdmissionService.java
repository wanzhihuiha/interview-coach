package com.interviewcoach.resume.infrastructure.redis;

import com.interviewcoach.common.exception.BusinessException;
import com.interviewcoach.resume.application.event.ResumeAiTaskLease;
import com.interviewcoach.resume.application.service.ResumeErrorCode;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RPermitExpirableSemaphore;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * 在任务提交虚拟线程前原子取得用户级和简历级可过期许可。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ResumeAiTaskAdmissionService {

    private final ObjectProvider<RedissonClient> redissonClientProvider;
    private final ResumeAiTaskProperties properties;

    /**
     * 固定按用户、简历顺序非阻塞获取许可；第二步失败时立即返还第一步许可。
     */
    public ResumeAiTaskLease acquire(Long userId, Long resumeId) {
        RPermitExpirableSemaphore userSemaphore = semaphore(userKey(userId), properties.getUserPermits());
        RPermitExpirableSemaphore resumeSemaphore = semaphore(
                resumeKey(userId, resumeId), properties.getResumePermits());
        String userPermitId = null;
        try {
            userPermitId = tryAcquire(userSemaphore);
            if (userPermitId == null) {
                throw new BusinessException(
                        ResumeErrorCode.USER_AI_CONCURRENCY_LIMIT, "当前用户的 AI 任务数已达上限");
            }
            String resumePermitId = tryAcquire(resumeSemaphore);
            if (resumePermitId == null) {
                tryRelease(userSemaphore, userPermitId, userId, resumeId);
                throw new BusinessException(
                        ResumeErrorCode.RESUME_AI_CONCURRENCY_LIMIT, "当前简历已有 AI 任务正在执行");
            }
            return new ResumeAiTaskLease(userPermitId, resumePermitId);
        } catch (BusinessException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (userPermitId != null) {
                tryRelease(userSemaphore, userPermitId, userId, resumeId);
            }
            throw unavailable("获取 AI 任务许可时线程被中断", e);
        } catch (RuntimeException e) {
            if (userPermitId != null) {
                tryRelease(userSemaphore, userPermitId, userId, resumeId);
            }
            throw unavailable("AI 任务许可服务不可用", e);
        }
    }

    /**
     * 同时续期两个许可；任一许可已过期或 Redis 异常都视为整个任务租约丢失。
     */
    public boolean renew(Long userId, Long resumeId, ResumeAiTaskLease lease) {
        if (!lease.isValid()) {
            return false;
        }
        try {
            boolean resumeRenewed = client().getPermitExpirableSemaphore(resumeKey(userId, resumeId))
                    .updateLeaseTime(
                            lease.resumePermitId(), properties.getLease().toMillis(), TimeUnit.MILLISECONDS);
            boolean userRenewed = client().getPermitExpirableSemaphore(userKey(userId))
                    .updateLeaseTime(
                            lease.userPermitId(), properties.getLease().toMillis(), TimeUnit.MILLISECONDS);
            return resumeRenewed && userRenewed;
        } catch (RuntimeException e) {
            log.warn("[ResumeTaskAdmission] 许可续期失败: userId={}, resumeId={}, errorType={}",
                    userId, resumeId, e.getClass().getSimpleName());
            return false;
        }
    }

    /**
     * 固定按简历、用户逆序尽力释放，重复释放或许可已自然过期不抛出业务异常。
     */
    public void release(Long userId, Long resumeId, ResumeAiTaskLease lease) {
        if (lease == null) {
            return;
        }
        tryRelease(resumeKey(userId, resumeId), lease.resumePermitId(), userId, resumeId);
        tryRelease(userKey(userId), lease.userPermitId(), userId, resumeId);
    }

    String userKey(Long userId) {
        return properties.getKeyPrefix() + ":permit:user:" + userId;
    }

    String resumeKey(Long userId, Long resumeId) {
        return properties.getKeyPrefix() + ":permit:user:" + userId + ":resume:" + resumeId;
    }

    private RPermitExpirableSemaphore semaphore(String key, int permits) {
        try {
            RPermitExpirableSemaphore semaphore = client().getPermitExpirableSemaphore(key);
            semaphore.trySetPermits(permits);
            return semaphore;
        } catch (RuntimeException e) {
            throw unavailable("AI 任务许可服务不可用", e);
        }
    }

    private String tryAcquire(RPermitExpirableSemaphore semaphore) throws InterruptedException {
        return semaphore.tryAcquire(
                properties.getAcquireWait().toMillis(),
                properties.getLease().toMillis(),
                TimeUnit.MILLISECONDS);
    }

    private void tryRelease(
            RPermitExpirableSemaphore semaphore, String permitId, Long userId, Long resumeId) {
        try {
            semaphore.tryRelease(permitId);
        } catch (RuntimeException e) {
            log.warn("[ResumeTaskAdmission] 许可释放失败，将等待租期回收: userId={}, resumeId={}, errorType={}",
                    userId, resumeId, e.getClass().getSimpleName());
        }
    }

    private void tryRelease(String key, String permitId, Long userId, Long resumeId) {
        try {
            tryRelease(client().getPermitExpirableSemaphore(key), permitId, userId, resumeId);
        } catch (RuntimeException e) {
            log.warn("[ResumeTaskAdmission] 许可释放失败，将等待租期回收: userId={}, resumeId={}, errorType={}",
                    userId, resumeId, e.getClass().getSimpleName());
        }
    }

    private RedissonClient client() {
        try {
            return redissonClientProvider.getObject();
        } catch (RuntimeException e) {
            throw unavailable("Redisson 客户端不可用", e);
        }
    }

    private BusinessException unavailable(String message, RuntimeException cause) {
        log.warn("[ResumeTaskAdmission] {}: errorType={}", message, cause.getClass().getSimpleName());
        return new BusinessException(
                ResumeErrorCode.RESUME_TASK_INFRASTRUCTURE_UNAVAILABLE,
                "AI 任务基础设施暂不可用，请稍后重试",
                cause);
    }

    private BusinessException unavailable(String message, InterruptedException cause) {
        log.warn("[ResumeTaskAdmission] {}", message);
        return new BusinessException(
                ResumeErrorCode.RESUME_TASK_INFRASTRUCTURE_UNAVAILABLE,
                "AI 任务基础设施暂不可用，请稍后重试",
                cause);
    }
}
