package com.interviewcoach.position.infrastructure.redis;

import com.interviewcoach.position.application.port.PositionAnalysisQueueStatusReader;
import com.interviewcoach.position.domain.model.PositionAnalysisQueueOwner;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/**
 * 岗位公平队列的可重建 Redis 投影；MySQL 当前任务始终是业务事实来源。
 * 启动恢复独占本地边界，恢复完成后的 Lua 和只读查询允许并发访问 Redis。
 * Lua 只保证同一 Redis Key 空间内的脚本原子性；Key 是否由多个应用实例共享，以及 Redis 持久化、重启或清空策略，当前证据不足。
 */
@Component
@RequiredArgsConstructor
public class PositionAnalysisRedisQueue implements PositionAnalysisQueueStatusReader {

    /** 岗位解析队列全部 Redis Key 的固定前缀；哈希标签 {@code {queue}} 使相关 Key 可落在同一集群槽位。 */
    private static final String KEY_PREFIX = "position:analysis:{queue}:";
    /** 每个参与者任务 ZSet 的 Key 前缀，后接服务端生成的 PUBLIC 或 USER 标识。 */
    private static final String OWNER_TASK_KEY_PREFIX = KEY_PREFIX + "owner:";
    /** 保存当前可轮转参与者顺序的 List Key。 */
    private static final String READY_QUEUE_KEY = KEY_PREFIX + "ready";
    /** 对 ready List 去重的参与者 Set Key。 */
    private static final String READY_MEMBER_KEY = KEY_PREFIX + "ready-members";
    /** 保存参与者到当前预留 taskId 映射的 Hash Key，一个参与者最多有一个 busy 任务。 */
    private static final String BUSY_OWNER_KEY = KEY_PREFIX + "busy";
    /** 记录存在岗位队列数据的参与者集合，供启动恢复发现并清理对应 ZSet。 */
    private static final String OWNER_INDEX_KEY = KEY_PREFIX + "owners";
    /** Lua 预留结果中分隔 queueOwner 与 taskId 的内部字符。 */
    private static final String RESERVATION_SEPARATOR = "|";

    /**
     * 幂等写入参与者任务 ZSet，并在参与者不 busy 且有等待任务时把它加入 ready 队尾。
     * 同一 taskId 的迟到重复事件不会把正在 busy 的任务重新加入等待集合。
     */
    private static final DefaultRedisScript<Long> ENQUEUE_SCRIPT = longScript("""
            local busyTask = redis.call('HGET', KEYS[4], ARGV[2])
            if busyTask ~= ARGV[1] then
                redis.call('ZADD', KEYS[1], 'NX', ARGV[1], ARGV[1])
            end
            redis.call('SADD', KEYS[5], ARGV[2])
            if not busyTask and redis.call('ZCARD', KEYS[1]) > 0 then
                if redis.call('SADD', KEYS[3], ARGV[2]) == 1 then
                    redis.call('RPUSH', KEYS[2], ARGV[2])
                end
            end
            return 1
            """);

    /**
     * 在单次 Lua 中按 ready 轮转弹出合法参与者的最小 taskId，并把该参与者写入 busy Hash。
     * 无效参与者和无任务参与者会被清理；返回值只是 Redis 预留，仍需 MySQL 条件领取确认。
     */
    private static final DefaultRedisScript<String> RESERVE_SCRIPT = stringScript("""
            local cycles = redis.call('LLEN', KEYS[1])
            for i = 1, cycles do
                local owner = redis.call('LPOP', KEYS[1])
                if owner then
                    redis.call('SREM', KEYS[2], owner)
                    local validOwner = owner == 'PUBLIC'
                        or string.match(owner, '^USER:[1-9][0-9]*$')
                    if not validOwner then
                        redis.call('SREM', KEYS[4], owner)
                    elseif redis.call('HEXISTS', KEYS[3], owner) == 0 then
                        local taskKey = ARGV[1] .. owner
                        while true do
                            local task = redis.call('ZRANGE', taskKey, 0, 0)[1]
                            if not task then
                                redis.call('SREM', KEYS[4], owner)
                                break
                            end
                            redis.call('ZREM', taskKey, task)
                            if string.match(task, '^[1-9][0-9]*$') then
                                redis.call('HSET', KEYS[3], owner, task)
                                return owner .. ARGV[2] .. task
                            end
                        end
                    end
                end
            end
            return nil
            """);

    /**
     * 仅当 busy 中的 taskId 仍与本次预留一致时释放参与者；若仍有等待任务则回到 ready 队尾，否则清理索引。
     */
    private static final DefaultRedisScript<Long> FINISH_SCRIPT = longScript("""
            if redis.call('HGET', KEYS[4], ARGV[1]) ~= ARGV[2] then
                return 0
            end
            redis.call('HDEL', KEYS[4], ARGV[1])
            if redis.call('ZCARD', KEYS[1]) > 0 then
                redis.call('SADD', KEYS[5], ARGV[1])
                if redis.call('SADD', KEYS[3], ARGV[1]) == 1 then
                    redis.call('RPUSH', KEYS[2], ARGV[1])
                end
            else
                redis.call('SREM', KEYS[5], ARGV[1])
                redis.call('SREM', KEYS[3], ARGV[1])
                redis.call('LREM', KEYS[2], 0, ARGV[1])
            end
            return 1
            """);

    /**
     * 仅当 busy 仍属于本次预留时，把 taskId 放回参与者 ZSet 并重新加入 ready，用于数据库领取结果未确认的恢复。
     */
    private static final DefaultRedisScript<Long> RESTORE_SCRIPT = longScript("""
            if redis.call('HGET', KEYS[4], ARGV[1]) ~= ARGV[2] then
                return 0
            end
            redis.call('ZADD', KEYS[1], 'NX', ARGV[2], ARGV[2])
            redis.call('HDEL', KEYS[4], ARGV[1])
            redis.call('SADD', KEYS[5], ARGV[1])
            if redis.call('SADD', KEYS[3], ARGV[1]) == 1 then
                redis.call('RPUSH', KEYS[2], ARGV[1])
            end
            return 1
            """);

    /**
     * 从参与者 ZSet 幂等移除已经不再 WAITING 的 taskId，并按剩余等待任务和 busy 状态修正 ready 与参与者索引。
     */
    private static final DefaultRedisScript<Long> REMOVE_WAITING_SCRIPT = longScript("""
            redis.call('ZREM', KEYS[1], ARGV[2])
            if redis.call('ZCARD', KEYS[1]) == 0
                    and redis.call('HEXISTS', KEYS[4], ARGV[1]) == 0 then
                redis.call('SREM', KEYS[5], ARGV[1])
                redis.call('SREM', KEYS[3], ARGV[1])
                redis.call('LREM', KEYS[2], 0, ARGV[1])
            elseif redis.call('HEXISTS', KEYS[4], ARGV[1]) == 0 then
                redis.call('SADD', KEYS[5], ARGV[1])
                if redis.call('SADD', KEYS[3], ARGV[1]) == 1 then
                    redis.call('RPUSH', KEYS[2], ARGV[1])
                end
            end
            return 1
            """);

    /**
     * 以当前 busy 数、ready 队列位置和参与者内 ZSet rank 计算前方任务估算值；未找到任务时返回内部哨兵 {@code -1}。
     */
    private static final DefaultRedisScript<Long> QUEUE_AHEAD_SCRIPT = longScript("""
            local rank = redis.call('ZRANK', KEYS[1], ARGV[2])
            if not rank then
                return -1
            end
            local readyOwners = redis.call('LRANGE', KEYS[2], 0, -1)
            local readyBefore = #readyOwners
            for index, owner in ipairs(readyOwners) do
                if owner == ARGV[1] then
                    readyBefore = index - 1
                    break
                end
            end
            local roundSize = #readyOwners
            if roundSize < 1 then
                roundSize = 1
            end
            return redis.call('HLEN', KEYS[3]) + readyBefore + rank * roundSize
            """);

    /** 执行岗位队列 Lua 和只读 Redis 命令的字符串客户端。 */
    private final StringRedisTemplate redisTemplate;
    /** 启动恢复完成前到达本进程的入队事件，按 taskId 暂存并与 MySQL 快照合并。 */
    private final Map<Long, QueueTask> pendingRecoveryTasks = new ConcurrentHashMap<>();
    /** 只隔离当前 JVM 的整体恢复与正常 Redis 访问，不是跨实例分布式锁。 */
    private final ReentrantReadWriteLock recoveryLock = new ReentrantReadWriteLock(true);
    /** {@code true} 表示当前 JVM 已完成岗位 Redis 投影重建，正常读写可以直达 Redis。 */
    private boolean recoveryComplete;

    /**
     * 同一 taskId 可重复投影；运行中任务不会因迟到的重复事件重新进入等待集合。
     */
    public void enqueue(Long taskId, String queueOwner) {
        validateTask(taskId, queueOwner);
        QueueTask task = new QueueTask(taskId, queueOwner);
        withRecoveryReadLock(() -> {
            if (!recoveryComplete) {
                // 启动重建前先保留本进程新到事件，稍后按 taskId 与数据库快照合并，避免被整体清理覆盖。
                pendingRecoveryTasks.put(taskId, task);
                return null;
            }
            // 恢复完成后通过 Lua 幂等维护 owner ZSet、ready 去重集合、轮转 List 和参与者索引。
            enqueueInternal(task);
            return null;
        });
    }

    /**
     * 原子弹出一个 ready 参与者的最早任务，并把参与者标记为 busy。
     */
    public Reservation reserveNext() {
        return withRecoveryReadLock(() -> {
            if (!recoveryComplete) {
                return null;
            }
            // Lua 原子完成 ready 弹出、最小 taskId 移除和 busy 写入；返回后仍须由 MySQL 判断能否进入 RUNNING。
            String value = redisTemplate.execute(
                    RESERVE_SCRIPT,
                    List.of(READY_QUEUE_KEY, READY_MEMBER_KEY, BUSY_OWNER_KEY, OWNER_INDEX_KEY),
                    OWNER_TASK_KEY_PREFIX,
                    RESERVATION_SEPARATOR);
            if (value == null) {
                return null;
            }
            int separator = value.indexOf(RESERVATION_SEPARATOR);
            if (separator <= 0 || separator == value.length() - 1) {
                throw new IllegalStateException("岗位 Redis 预留结果格式无效");
            }
            String queueOwner = value.substring(0, separator);
            Long taskId;
            try {
                taskId = Long.valueOf(value.substring(separator + 1));
            } catch (NumberFormatException e) {
                throw new IllegalStateException("岗位 Redis 预留任务 ID 无效", e);
            }
            validateTask(taskId, queueOwner);
            return new Reservation(taskId, queueOwner);
        });
    }

    /**
     * DB 领取成功后的 Worker 真正结束时清除 busy，并把仍有等待任务的参与者放回队尾。
     */
    public boolean finish(Reservation reservation) {
        validateReservation(reservation);
        return withRecoveryReadLock(() -> {
            if (!recoveryComplete) {
                return false;
            }
            // 只有 taskId 仍匹配本次预留才释放 busy，防止迟到 Worker 触碰新的参与者占用。
            Long result = redisTemplate.execute(
                    FINISH_SCRIPT,
                    ownerKeys(reservation.queueOwner()),
                    reservation.queueOwner(),
                    reservation.taskId().toString());
            return Long.valueOf(1L).equals(result);
        });
    }

    /**
     * DB 领取出现未确认结果时恢复刚弹出的任务，并结束本次 busy 预留。
     */
    public boolean restore(Reservation reservation) {
        validateReservation(reservation);
        return withRecoveryReadLock(() -> {
            if (!recoveryComplete) {
                return false;
            }
            // DB 领取结果未确认时，按原 taskId 原子放回 ZSet 并恢复 ready；预留已变化则返回 false。
            Long result = redisTemplate.execute(
                    RESTORE_SCRIPT,
                    ownerKeys(reservation.queueOwner()),
                    reservation.queueOwner(),
                    reservation.taskId().toString());
            return Long.valueOf(1L).equals(result);
        });
    }

    /**
     * 归档等事务删除 WAITING 任务后移除投影；busy 任务必须等 Worker 真正结束。
     */
    public void removeWaiting(Long taskId, String queueOwner) {
        validateTask(taskId, queueOwner);
        withRecoveryReadLock(() -> {
            if (!recoveryComplete) {
                // 启动恢复前的删除事件从本进程 pending 中移除；MySQL 快照本身已不会包含已删除任务。
                pendingRecoveryTasks.remove(taskId);
                return null;
            }
            // Lua 只删除 WAITING 投影并修正参与者索引；busy 任务仍由 Worker 真实结束后释放。
            redisTemplate.execute(
                    REMOVE_WAITING_SCRIPT,
                    ownerKeys(queueOwner),
                    queueOwner,
                    taskId.toString());
            return null;
        });
    }

    /**
     * 判断岗位 ready List 当前是否存在参与者；恢复未完成时固定返回 {@code false}。
     *
     * @return 当前 Redis 快照中是否有可尝试领取的参与者
     */
    public boolean hasReadyParticipant() {
        return withRecoveryReadLock(() -> {
            if (!recoveryComplete) {
                return false;
            }
            Long size = redisTemplate.opsForList().size(READY_QUEUE_KEY);
            return size != null && size > 0;
        });
    }

    /**
     * 结合当前 busy 数、ready 位置和 owner 内排序估算前方任务；该值不是完成时间承诺。
     */
    @Override
    public Long queueAhead(Long taskId, String queueOwner) {
        validateTask(taskId, queueOwner);
        return withRecoveryReadLock(() -> {
            if (!recoveryComplete) {
                return null;
            }
            // 单次 Lua 基于同一时刻的 owner rank、ready 顺序和 busy 数生成页面估算快照。
            Long result = redisTemplate.execute(
                    QUEUE_AHEAD_SCRIPT,
                    List.of(ownerTaskKey(queueOwner), READY_QUEUE_KEY, BUSY_OWNER_KEY),
                    queueOwner,
                    taskId.toString());
            return result == null || result < 0 ? null : result;
        });
    }

    /**
     * 启动恢复期间独占当前 JVM 的队列访问，删除已登记的岗位 ready、busy、参与者索引和任务 ZSet 后按 taskId 重建轮转。
     * 数据库 WAITING 快照与恢复期间本地 pending 事件按 taskId 合并；该本地锁不协调其他应用实例。
     *
     * @param tasks MySQL 收口后仍有效的 WAITING 任务快照
     */
    public void resetAndRestore(Collection<QueueTask> tasks) {
        Lock lock = recoveryLock.writeLock();
        lock.lock();
        try {
            Map<Long, QueueTask> mergedTasks = new LinkedHashMap<>();
            if (tasks != null) {
                tasks.forEach(task -> mergedTasks.put(task.taskId(), task));
            }
            // 同 taskId 的启动期间事件覆盖快照值，再整体按 taskId 排序以恢复稳定参与者轮转。
            mergedTasks.putAll(pendingRecoveryTasks);
            List<QueueTask> orderedTasks = mergedTasks.values().stream()
                    .sorted(Comparator.comparing(QueueTask::taskId))
                    .toList();
            Set<String> owners = new LinkedHashSet<>();
            // 同时读取旧参与者索引和新快照参与者，确保旧 owner ZSet 也进入精确删除集合。
            Set<String> indexedOwners = redisTemplate.opsForSet().members(OWNER_INDEX_KEY);
            if (indexedOwners != null) {
                owners.addAll(indexedOwners);
            }
            for (QueueTask task : orderedTasks) {
                validateTask(task.taskId(), task.queueOwner());
                owners.add(task.queueOwner());
            }

            List<String> keys = new ArrayList<>(List.of(
                    READY_QUEUE_KEY, READY_MEMBER_KEY, BUSY_OWNER_KEY, OWNER_INDEX_KEY));
            owners.stream().map(PositionAnalysisRedisQueue::ownerTaskKey).forEach(keys::add);
            // 清空岗位队列投影后再重放 MySQL 快照；删除与重建不是数据库事务，也不代表 Redis 持久化保证。
            redisTemplate.delete(keys);

            for (QueueTask task : orderedTasks) {
                // 逐任务复用入队 Lua，恢复参与者内 FIFO 和跨参与者 ready 轮转。
                enqueueInternal(task);
            }
            pendingRecoveryTasks.clear();
            recoveryComplete = true;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 共享锁只用于隔离启动重建，不把正常 Redis 往返重新串行化到单个 JVM 监视器。
     */
    private <T> T withRecoveryReadLock(Supplier<T> action) {
        Lock lock = recoveryLock.readLock();
        lock.lock();
        try {
            return action.get();
        } finally {
            lock.unlock();
        }
    }

    /** 使用入队 Lua 同步维护指定任务的参与者 ZSet、ready 结构和参与者索引。 */
    private void enqueueInternal(QueueTask task) {
        redisTemplate.execute(
                ENQUEUE_SCRIPT,
                ownerKeys(task.queueOwner()),
                task.taskId().toString(),
                task.queueOwner());
    }

    /** 返回单个参与者脚本共同使用的五个岗位 Redis Key，顺序与 Lua 的 KEYS 索引一致。 */
    private List<String> ownerKeys(String queueOwner) {
        return List.of(
                ownerTaskKey(queueOwner),
                READY_QUEUE_KEY,
                READY_MEMBER_KEY,
                BUSY_OWNER_KEY,
                OWNER_INDEX_KEY);
    }

    /** 校验预留对象及其中 taskId、queueOwner 均为服务端支持的值。 */
    private void validateReservation(Reservation reservation) {
        if (reservation == null) {
            throw new IllegalArgumentException("岗位队列预留不能为空");
        }
        validateTask(reservation.taskId(), reservation.queueOwner());
    }

    /** 拒绝非正数任务 ID 或非 PUBLIC/规范 USER 的参与者，避免构造意外 Redis Key。 */
    private void validateTask(Long taskId, String queueOwner) {
        if (taskId == null || taskId <= 0) {
            throw new IllegalArgumentException("岗位任务 ID 必须为正数");
        }
        if (!PositionAnalysisQueueOwner.isValid(queueOwner)) {
            throw new IllegalArgumentException("岗位队列参与者无效");
        }
    }

    /** 根据已验证的参与者标识构造其岗位等待任务 ZSet Key。 */
    private static String ownerTaskKey(String queueOwner) {
        return OWNER_TASK_KEY_PREFIX + queueOwner;
    }

    /** 把脚本文本声明为返回 Long 的 Spring Redis Lua 对象。 */
    private static DefaultRedisScript<Long> longScript(String scriptText) {
        return new DefaultRedisScript<>(scriptText, Long.class);
    }

    /** 把脚本文本声明为返回字符串预留结果的 Spring Redis Lua 对象。 */
    private static DefaultRedisScript<String> stringScript(String scriptText) {
        return new DefaultRedisScript<>(scriptText, String.class);
    }

    /**
     * 启动恢复和投影入队使用的不可变任务数据。
     *
     * @param taskId MySQL 当前任务的正数 ID，同时作为参与者内 FIFO 分值
     * @param queueOwner 服务端生成的 PUBLIC 或 USER 队列参与者
     */
    public record QueueTask(Long taskId, String queueOwner) {
    }

    /**
     * Redis 已从等待集合弹出并写入 busy 的预留身份，供后续 finish/restore 做所有者匹配。
     *
     * @param taskId 本次 Redis 预留的任务 ID
     * @param queueOwner 当前 busy 的参与者标识
     */
    public record Reservation(Long taskId, String queueOwner) {
    }
}
