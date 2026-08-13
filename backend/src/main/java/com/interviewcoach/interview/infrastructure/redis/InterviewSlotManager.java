package com.interviewcoach.interview.infrastructure.redis;

import com.interviewcoach.interview.infrastructure.config.InterviewSlotProperties;
import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/**
 * 面试创建流程使用的 Redis 并发槽位管理器。
 *
 * <p>共享全局计数 Key 记录占槽数量，面试级 Key 提供幂等占用标记。Lua 在同一 Redis 执行中
 * 原子判断上限并更新两类 Key；自然/主动结束显式释放。单槽 Key 自然 TTL 过期只删除标记，
 * 不会执行释放脚本或递减全局计数，这是当前实现限制。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InterviewSlotManager {

    /** 共享 Redis Key 空间内的全局活跃槽位计数 Key，本身没有设置 TTL。 */
    private static final String ACTIVE_COUNT_KEY = "interview:active:count";
    /** 面试级幂等槽位标记前缀，后接服务端面试 ID。 */
    private static final String ACTIVE_SLOT_KEY_PREFIX = "interview:active:slot:";

    /**
     * 原子占槽脚本：已有单槽 Key 按幂等成功返回 1；否则全局计数达到上限返回 0，未达到时
     * 计数加一并写入带 TTL 的面试级标记。
     */
    private static final String ACQUIRE_SCRIPT = """
            local countKey = KEYS[1]
            local slotKey = KEYS[2]
            local maxConcurrent = tonumber(ARGV[1])
            local ttlSeconds = tonumber(ARGV[2])

            if redis.call('EXISTS', slotKey) == 1 then
                return 1
            end

            local current = redis.call('GET', countKey)
            if current and tonumber(current) >= maxConcurrent then
                return 0
            end

            redis.call('INCR', countKey)
            redis.call('SET', slotKey, '1', 'EX', ttlSeconds)
            return 1
            """;

    /**
     * 原子释放脚本：仅删除到面试级标记时才递减全局计数；标记已不存在则只返回当前计数。
     * 递减后若出现负数会将计数纠正为 0。
     */
    private static final String RELEASE_SCRIPT = """
            local countKey = KEYS[1]
            local slotKey = KEYS[2]

            if redis.call('DEL', slotKey) == 0 then
                return redis.call('GET', countKey)
            end

            local current = redis.call('DECR', countKey)
            if current < 0 then
                redis.call('SET', countKey, '0')
                current = 0
            end
            return current
            """;

    /** 负责在共享 Redis Key 空间执行槽位 Lua 与读取监控计数。 */
    private final StringRedisTemplate redisTemplate;
    /** 提供最大并发数和面试级标记 TTL。 */
    private final InterviewSlotProperties properties;

    /** 初始化后复用的占槽 Lua 脚本对象。 */
    private DefaultRedisScript<Long> acquireScript;
    /** 初始化后复用的释放 Lua 脚本对象。 */
    private DefaultRedisScript<Long> releaseScript;

    /** Spring 注入完成后装配两段 Lua，并声明 Long 返回类型。 */
    @PostConstruct
    public void init() {
        acquireScript = new DefaultRedisScript<>();
        acquireScript.setScriptText(ACQUIRE_SCRIPT);
        acquireScript.setResultType(Long.class);

        releaseScript = new DefaultRedisScript<>();
        releaseScript.setScriptText(RELEASE_SCRIPT);
        releaseScript.setResultType(Long.class);
    }

    /**
     * 尝试为指定面试幂等占用一个槽位。
     *
     * @param interviewId 面试 ID
     * @return 脚本返回 1 时为 true；达到上限、空结果或其他返回值为 false
     */
    public boolean acquireSlot(Long interviewId) {
        // Lua 同时接收全局计数 Key 和面试级 Key，在 Redis 端原子判断、递增和设置 TTL。
        Long result = redisTemplate.execute(
                acquireScript,
                List.of(ACTIVE_COUNT_KEY, slotKey(interviewId)),
                String.valueOf(properties.getMaxConcurrent()),
                String.valueOf(Duration.ofMinutes(properties.getTtlMinutes()).getSeconds()));
        boolean acquired = result != null && result == 1L;
        if (acquired) {
            log.info("[InterviewSlot] 占用槽位成功 interviewId={}", interviewId);
        } else {
            log.warn("[InterviewSlot] 槽位已满，拒绝新面试 maxConcurrent={}", properties.getMaxConcurrent());
        }
        return acquired;
    }

    /**
     * 幂等释放指定面试槽位；只有面试级标记仍存在时才递减全局计数。
     *
     * @param interviewId 面试 ID
     */
    public void releaseSlot(Long interviewId) {
        // Lua 原子删除面试级标记并按需递减计数；Redis 异常向调用方传播。
        Long current = redisTemplate.execute(
                releaseScript,
                List.of(ACTIVE_COUNT_KEY, slotKey(interviewId)));
        log.info("[InterviewSlot] 释放槽位 interviewId={} 当前活跃数={}", interviewId, current);
    }

    /**
     * 读取全局计数供监控；Key 不存在返回 0，非数字值会抛出解析异常。
     * 该计数可能因单槽 Key 自然过期而高于实际仍存在的槽位标记数。
     */
    public long getActiveCount() {
        String value = redisTemplate.opsForValue().get(ACTIVE_COUNT_KEY);
        return value == null ? 0L : Long.parseLong(value);
    }

    /** 用服务端面试 ID 拼接面试级槽位 Key；调用方必须提供非空有效 ID。 */
    private String slotKey(Long interviewId) {
        return ACTIVE_SLOT_KEY_PREFIX + interviewId;
    }
}
