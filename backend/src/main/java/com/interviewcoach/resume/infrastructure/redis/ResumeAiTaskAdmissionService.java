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
 * 在任务提交虚拟线程前顺序取得用户级、简历级可过期许可；第二步失败时补偿第一步，两个许可并非一次原子操作。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ResumeAiTaskAdmissionService {

    /** 延迟取得 Redisson 客户端，Redis 不可用时统一失败关闭。 */
    private final ObjectProvider<RedissonClient> redissonClientProvider;
    /** 提供许可数量、可配置等待时间、租期和 Key 前缀。 */
    private final ResumeAiTaskProperties properties;

    /**
     * 固定按用户、简历顺序获取许可；每步最多等待 {@code acquireWait}，第二步失败时立即返还第一步许可。
     */
    public ResumeAiTaskLease acquire(Long userId, Long resumeId) {
        // 按配置初始化或读取两个可过期信号量；实际 Key 是否跨实例共享取决于部署 Redis 配置。
        RPermitExpirableSemaphore userSemaphore = semaphore(userKey(userId), properties.getUserPermits());
        RPermitExpirableSemaphore resumeSemaphore = semaphore(
                resumeKey(userId, resumeId), properties.getResumePermits());
        String userPermitId = null;
        try {
            // 先取得用户级许可，限制该用户全部简历 AI 任务的总并发。
            userPermitId = tryAcquire(userSemaphore);
            if (userPermitId == null) {
                throw new BusinessException(
                        ResumeErrorCode.USER_AI_CONCURRENCY_LIMIT, "当前用户的 AI 任务数已达上限");
            }
            // 再取得简历级许可，互斥同一简历的事实解析和辅助分析；失败则返还用户许可。
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
     * 依次续期简历级和用户级许可；任一许可已过期或 Redis 异常都视为整个任务租约丢失，两个续期并非原子操作。
     */
    public boolean renew(Long userId, Long resumeId, ResumeAiTaskLease lease) {
        if (!lease.isValid()) {
            return false;
        }
        try {
            // 两个许可分别续期；任一步返回 false 都使整个本地租约失效，但已成功的单步续期不会回滚。
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

    /** 构造用户级许可 Key，覆盖该用户的全部简历 AI 任务。 */
    String userKey(Long userId) {
        return properties.getKeyPrefix() + ":permit:user:" + userId;
    }

    /** 构造用户和简历双重隔离的许可 Key。 */
    String resumeKey(Long userId, Long resumeId) {
        return properties.getKeyPrefix() + ":permit:user:" + userId + ":resume:" + resumeId;
    }

    /** 获取可过期信号量并仅在首次创建时设置许可数，Redis 异常统一失败关闭。 */
    private RPermitExpirableSemaphore semaphore(String key, int permits) {
        try {
            RPermitExpirableSemaphore semaphore = client().getPermitExpirableSemaphore(key);
            semaphore.trySetPermits(permits);
            return semaphore;
        } catch (RuntimeException e) {
            throw unavailable("AI 任务许可服务不可用", e);
        }
    }

    /** 按配置等待时间和租期尝试获取许可；未取得时返回 null。 */
    private String tryAcquire(RPermitExpirableSemaphore semaphore) throws InterruptedException {
        return semaphore.tryAcquire(
                properties.getAcquireWait().toMillis(),
                properties.getLease().toMillis(),
                TimeUnit.MILLISECONDS);
    }

    /** 按 permit ID 尽力释放已取得许可；失败时等待许可自然过期。 */
    private void tryRelease(
            RPermitExpirableSemaphore semaphore, String permitId, Long userId, Long resumeId) {
        try {
            semaphore.tryRelease(permitId);
        } catch (RuntimeException e) {
            log.warn("[ResumeTaskAdmission] 许可释放失败，将等待租期回收: userId={}, resumeId={}, errorType={}",
                    userId, resumeId, e.getClass().getSimpleName());
        }
    }

    /** 按 Key 重新取得信号量后尽力释放许可，客户端不可用时只记录安全摘要。 */
    private void tryRelease(String key, String permitId, Long userId, Long resumeId) {
        try {
            tryRelease(client().getPermitExpirableSemaphore(key), permitId, userId, resumeId);
        } catch (RuntimeException e) {
            log.warn("[ResumeTaskAdmission] 许可释放失败，将等待租期回收: userId={}, resumeId={}, errorType={}",
                    userId, resumeId, e.getClass().getSimpleName());
        }
    }

    /** 获取 Redisson 客户端，并将装配或连接异常映射为统一业务错误。 */
    private RedissonClient client() {
        try {
            return redissonClientProvider.getObject();
        } catch (RuntimeException e) {
            throw unavailable("Redisson 客户端不可用", e);
        }
    }

    /** 将运行时基础设施异常映射为用户可重试的失败关闭错误。 */
    private BusinessException unavailable(String message, RuntimeException cause) {
        log.warn("[ResumeTaskAdmission] {}: errorType={}", message, cause.getClass().getSimpleName());
        return new BusinessException(
                ResumeErrorCode.RESUME_TASK_INFRASTRUCTURE_UNAVAILABLE,
                "AI 任务基础设施暂不可用，请稍后重试",
                cause);
    }

    /** 将获取许可时的线程中断映射为失败关闭错误，调用方已恢复中断标志。 */
    private BusinessException unavailable(String message, InterruptedException cause) {
        log.warn("[ResumeTaskAdmission] {}", message);
        return new BusinessException(
                ResumeErrorCode.RESUME_TASK_INFRASTRUCTURE_UNAVAILABLE,
                "AI 任务基础设施暂不可用，请稍后重试",
                cause);
    }
}
