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
 * 跨实例串行仅在各实例连接共享的 Redis Key 空间时成立，当前部署是否满足该前提尚无证据。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ResumeUserMutationLock {

    /** 延迟取得 Redisson 客户端，基础设施不可用时统一失败关闭。 */
    private final ObjectProvider<RedissonClient> redissonClientProvider;
    /** 提供用户变更锁 Key 的公共前缀。 */
    private final ResumeAiTaskProperties properties;

    /** 在普通用户变更锁内执行动作；动作结束后不额外要求锁仍由当前线程持有。 */
    public <T> T execute(Long userId, Supplier<T> action) {
        return execute(userId, action, false);
    }

    /**
     * 上传使用严格模式：动作完成后若锁已丢失则抛错，由上传编排删除本次数据库记录和文件。
     */
    public <T> T executeChecked(Long userId, Supplier<T> action) {
        return execute(userId, action, true);
    }

    /** 获取用户级非等待锁、执行动作并按模式复核归属，最终仅由持有线程释放。 */
    private <T> T execute(Long userId, Supplier<T> action, boolean verifyOwnershipAfterAction) {
        RLock lock;
        try {
            // 锁 Key 只含服务端用户 ID；获取失败立即拒绝，避免并发上传和删除绕过数量复查。
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
            // 在锁覆盖范围内执行调用方提供的数据库/文件编排；动作异常继续向外传播。
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

    /** 仅当当前线程仍持有锁时释放；释放异常记录安全摘要而不覆盖原结果。 */
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

    /** 将 Redisson 获取或查询异常映射为统一的任务基础设施业务错误。 */
    private BusinessException unavailable(RuntimeException cause) {
        log.warn("[ResumeMutation] 用户变更锁不可用: errorType={}", cause.getClass().getSimpleName());
        return new BusinessException(
                ResumeErrorCode.RESUME_TASK_INFRASTRUCTURE_UNAVAILABLE,
                "简历任务基础设施暂不可用，请稍后重试",
                cause);
    }
}
