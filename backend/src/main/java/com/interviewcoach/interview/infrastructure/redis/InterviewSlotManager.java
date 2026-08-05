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
 * 面试并发槽位管理器。
 *
 * <p>使用 Redis 原子计数器维护当前正在进行的面试数量，超过上限时直接拒绝新面试创建。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InterviewSlotManager {

    private static final String ACTIVE_COUNT_KEY = "interview:active:count";
    private static final String ACTIVE_SLOT_KEY_PREFIX = "interview:active:slot:";

    /**
     * 原子占槽：若当前活跃数未达上限且该面试尚未占槽，则计数 +1 并设置槽位标记。
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
     * 原子释放：仅当槽位标记存在时才删除并计数 -1，防止计数出现负数或误减旧面试。
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

    private final StringRedisTemplate redisTemplate;
    private final InterviewSlotProperties properties;

    private DefaultRedisScript<Long> acquireScript;
    private DefaultRedisScript<Long> releaseScript;

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
     * 尝试占用一个面试槽位。
     *
     * @param interviewId 面试 ID
     * @return true 占用成功，false 已达上限
     */
    public boolean acquireSlot(Long interviewId) {
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
     * 释放面试槽位，幂等操作。
     *
     * @param interviewId 面试 ID
     */
    public void releaseSlot(Long interviewId) {
        Long current = redisTemplate.execute(
                releaseScript,
                List.of(ACTIVE_COUNT_KEY, slotKey(interviewId)));
        log.info("[InterviewSlot] 释放槽位 interviewId={} 当前活跃数={}", interviewId, current);
    }

    /**
     * 获取当前活跃面试数，用于监控。
     */
    public long getActiveCount() {
        String value = redisTemplate.opsForValue().get(ACTIVE_COUNT_KEY);
        return value == null ? 0L : Long.parseLong(value);
    }

    private String slotKey(Long interviewId) {
        return ACTIVE_SLOT_KEY_PREFIX + interviewId;
    }
}
