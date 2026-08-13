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
 * 在同一 Redisson Key 空间内串行公共岗位创建和重解析的等待上限检查及数据库提交。
 * Redis 不可用或未取得锁时失败关闭；部署中的所有实例是否共享该 Key 空间，当前证据不足。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PositionPublicSubmissionLock {

    /** 公共岗位提交使用的固定 Redisson 锁 Key；不含管理员身份，所有公共提交竞争同一 Key。 */
    private static final String LOCK_KEY = "position:analysis:{queue}:public-submission-lock";

    /** 延迟取得项目配置的 Redisson 客户端；缺少或不可用时公共提交直接失败。 */
    private final ObjectProvider<RedissonClient> redissonClientProvider;

    /**
     * 锁覆盖完整数据库事务提交；未取得锁时不执行上限检查和写入。
     * 调用动作抛出的业务或事务异常原样传播，最终块仍尝试释放当前线程持有的锁。
     *
     * @param action 持锁期间执行的公共岗位登记或重新解析事务
     * @param <T> 事务返回结果类型
     * @return 数据库事务成功提交后的结果
     */
    public <T> T execute(Supplier<T> action) {
        RLock lock;
        try {
            // 从项目 Redisson 客户端取得固定 Key，并以非阻塞方式竞争公共提交临界区。
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
            // 锁一直持有到 Supplier 内的数据库事务返回，使等待上限检查与写入不被同 Key 提交穿透。
            return action.get();
        } finally {
            // 仅当前线程仍持有锁时释放，避免删除已经不属于本次调用的锁。
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
