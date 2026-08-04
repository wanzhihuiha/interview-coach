package com.interviewcoach.position.infrastructure.redis;

import com.interviewcoach.common.exception.BusinessException;
import com.interviewcoach.position.application.service.PositionErrorCode;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * 用固定 Redisson 锁串行公共岗位创建和重试的等待上限检查；Redis 不可用时失败关闭。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PositionPublicSubmissionLock {

    private static final String LOCK_KEY = "position:analysis:{queue}:public-submission-lock";

    private final ObjectProvider<RedissonClient> redissonClientProvider;

    /**
     * 锁覆盖完整数据库事务提交；未取得锁时不执行上限检查和写入。
     */
    public <T> T execute(Supplier<T> action) {
        RLock lock;
        try {
            lock = redissonClientProvider.getObject().getLock(LOCK_KEY);
            if (!lock.tryLock()) {
                throw new BusinessException(
                        PositionErrorCode.POSITION_PUBLIC_SUBMISSION_BUSY,
                        "公共岗位正在提交，请稍后重试");
            }
        } catch (BusinessException e) {
            throw e;
        } catch (RuntimeException e) {
            throw unavailable(e);
        }

        try {
            return action.get();
        } finally {
            unlockQuietly(lock);
        }
    }

    private void unlockQuietly(RLock lock) {
        try {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        } catch (RuntimeException e) {
            log.warn("[PositionAnalysis] 公共提交锁释放失败: errorType={}",
                    e.getClass().getSimpleName());
        }
    }

    private BusinessException unavailable(RuntimeException cause) {
        log.warn("[PositionAnalysis] 公共提交锁不可用: errorType={}",
                cause.getClass().getSimpleName());
        return new BusinessException(
                PositionErrorCode.POSITION_INFRASTRUCTURE_UNAVAILABLE,
                "岗位任务基础设施暂不可用，请稍后重试",
                cause);
    }
}
