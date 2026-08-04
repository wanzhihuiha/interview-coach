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
 */
@Component
@RequiredArgsConstructor
public class PositionAnalysisRedisQueue implements PositionAnalysisQueueStatusReader {

    private static final String KEY_PREFIX = "position:analysis:{queue}:";
    private static final String OWNER_TASK_KEY_PREFIX = KEY_PREFIX + "owner:";
    private static final String READY_QUEUE_KEY = KEY_PREFIX + "ready";
    private static final String READY_MEMBER_KEY = KEY_PREFIX + "ready-members";
    private static final String BUSY_OWNER_KEY = KEY_PREFIX + "busy";
    private static final String OWNER_INDEX_KEY = KEY_PREFIX + "owners";
    private static final String RESERVATION_SEPARATOR = "|";

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

    private final StringRedisTemplate redisTemplate;
    private final Map<Long, QueueTask> pendingRecoveryTasks = new ConcurrentHashMap<>();
    private final ReentrantReadWriteLock recoveryLock = new ReentrantReadWriteLock(true);
    private boolean recoveryComplete;

    /**
     * 同一 taskId 可重复投影；运行中任务不会因迟到的重复事件重新进入等待集合。
     */
    public void enqueue(Long taskId, String queueOwner) {
        validateTask(taskId, queueOwner);
        QueueTask task = new QueueTask(taskId, queueOwner);
        withRecoveryReadLock(() -> {
            if (!recoveryComplete) {
                pendingRecoveryTasks.put(taskId, task);
                return null;
            }
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
                pendingRecoveryTasks.remove(taskId);
                return null;
            }
            redisTemplate.execute(
                    REMOVE_WAITING_SCRIPT,
                    ownerKeys(queueOwner),
                    queueOwner,
                    taskId.toString());
            return null;
        });
    }

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
            Long result = redisTemplate.execute(
                    QUEUE_AHEAD_SCRIPT,
                    List.of(ownerTaskKey(queueOwner), READY_QUEUE_KEY, BUSY_OWNER_KEY),
                    queueOwner,
                    taskId.toString());
            return result == null || result < 0 ? null : result;
        });
    }

    /**
     * 启动恢复期间独占本地队列访问，删除已登记的本 Goal Key 后按 taskId 重建轮转。
     */
    public void resetAndRestore(Collection<QueueTask> tasks) {
        Lock lock = recoveryLock.writeLock();
        lock.lock();
        try {
            Map<Long, QueueTask> mergedTasks = new LinkedHashMap<>();
            if (tasks != null) {
                tasks.forEach(task -> mergedTasks.put(task.taskId(), task));
            }
            mergedTasks.putAll(pendingRecoveryTasks);
            List<QueueTask> orderedTasks = mergedTasks.values().stream()
                    .sorted(Comparator.comparing(QueueTask::taskId))
                    .toList();
            Set<String> owners = new LinkedHashSet<>();
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
            redisTemplate.delete(keys);

            for (QueueTask task : orderedTasks) {
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

    private void enqueueInternal(QueueTask task) {
        redisTemplate.execute(
                ENQUEUE_SCRIPT,
                ownerKeys(task.queueOwner()),
                task.taskId().toString(),
                task.queueOwner());
    }

    private List<String> ownerKeys(String queueOwner) {
        return List.of(
                ownerTaskKey(queueOwner),
                READY_QUEUE_KEY,
                READY_MEMBER_KEY,
                BUSY_OWNER_KEY,
                OWNER_INDEX_KEY);
    }

    private void validateReservation(Reservation reservation) {
        if (reservation == null) {
            throw new IllegalArgumentException("岗位队列预留不能为空");
        }
        validateTask(reservation.taskId(), reservation.queueOwner());
    }

    private void validateTask(Long taskId, String queueOwner) {
        if (taskId == null || taskId <= 0) {
            throw new IllegalArgumentException("岗位任务 ID 必须为正数");
        }
        if (!PositionAnalysisQueueOwner.isValid(queueOwner)) {
            throw new IllegalArgumentException("岗位队列参与者无效");
        }
    }

    private static String ownerTaskKey(String queueOwner) {
        return OWNER_TASK_KEY_PREFIX + queueOwner;
    }

    private static DefaultRedisScript<Long> longScript(String scriptText) {
        return new DefaultRedisScript<>(scriptText, Long.class);
    }

    private static DefaultRedisScript<String> stringScript(String scriptText) {
        return new DefaultRedisScript<>(scriptText, String.class);
    }

    public record QueueTask(Long taskId, String queueOwner) {
    }

    public record Reservation(Long taskId, String queueOwner) {
    }
}
