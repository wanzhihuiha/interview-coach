package com.interviewcoach.resume.infrastructure.redis;

import com.interviewcoach.common.exception.BusinessException;
import com.interviewcoach.resume.application.service.ResumeErrorCode;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 以 Lua 原子预留并确认每天最多新建五份简历；删除不会调用本服务返还计数。
 */
@Slf4j
@Component
public class ResumeDailyCreationQuotaService {

    private static final DefaultRedisScript<Long> RESERVE_SCRIPT = script("""
            local tokenField = 'token:' .. ARGV[1]
            if redis.call('HEXISTS', KEYS[1], tokenField) == 1 then return 2 end
            local created = tonumber(redis.call('HGET', KEYS[1], 'created') or '0')
            local reserved = tonumber(redis.call('HGET', KEYS[1], 'reserved') or '0')
            if created + reserved >= tonumber(ARGV[2]) then return -1 end
            redis.call('HINCRBY', KEYS[1], 'reserved', 1)
            redis.call('HSET', KEYS[1], tokenField, 'RESERVED')
            redis.call('EXPIRE', KEYS[1], tonumber(ARGV[3]))
            return 1
            """);

    private static final DefaultRedisScript<Long> COMMIT_SCRIPT = script("""
            local tokenField = 'token:' .. ARGV[1]
            local state = redis.call('HGET', KEYS[1], tokenField)
            if state == 'CREATED' then return 2 end
            if state ~= 'RESERVED' then return 0 end
            redis.call('HINCRBY', KEYS[1], 'reserved', -1)
            redis.call('HINCRBY', KEYS[1], 'created', 1)
            redis.call('HSET', KEYS[1], tokenField, 'CREATED')
            return 1
            """);

    private static final DefaultRedisScript<Long> RELEASE_SCRIPT = script("""
            local tokenField = 'token:' .. ARGV[1]
            local state = redis.call('HGET', KEYS[1], tokenField)
            if state == 'RELEASED' then return 2 end
            if state == 'RESERVED' then
                redis.call('HINCRBY', KEYS[1], 'reserved', -1)
            elseif state == 'CREATED' then
                redis.call('HINCRBY', KEYS[1], 'created', -1)
            else
                return 0
            end
            redis.call('HSET', KEYS[1], tokenField, 'RELEASED')
            return 1
            """);

    private final ResumeRedisScriptExecutor scriptExecutor;
    private final ResumeAiTaskProperties properties;
    private final Clock clock;

    @Autowired
    public ResumeDailyCreationQuotaService(
            ResumeRedisScriptExecutor scriptExecutor, ResumeAiTaskProperties properties) {
        this(scriptExecutor, properties, Clock.system(properties.zoneId()));
    }

    ResumeDailyCreationQuotaService(
            ResumeRedisScriptExecutor scriptExecutor, ResumeAiTaskProperties properties, Clock clock) {
        this.scriptExecutor = scriptExecutor;
        this.properties = properties;
        this.clock = clock;
    }

    public CreationReservation reserve(Long userId) {
        LocalDate quotaDate = LocalDate.now(clock);
        CreationReservation reservation = new CreationReservation(
                quotaDate, UUID.randomUUID().toString().replace("-", ""));
        Long result = execute(
                RESERVE_SCRIPT,
                key(userId, quotaDate),
                reservation.token(),
                Integer.toString(properties.getDailyCreates()),
                Long.toString(ttlSeconds(quotaDate)));
        if (result == -1L) {
            throw new BusinessException(
                    ResumeErrorCode.DAILY_RESUME_CREATE_LIMIT, "今日新建简历数量已达上限");
        }
        if (result != 1L && result != 2L) {
            throw unavailable(null);
        }
        return reservation;
    }

    public boolean commit(Long userId, CreationReservation reservation) {
        Long result = execute(COMMIT_SCRIPT, key(userId, reservation.quotaDate()), reservation.token());
        return result == 1L || result == 2L;
    }

    public boolean release(Long userId, CreationReservation reservation) {
        if (reservation == null) {
            return true;
        }
        Long result = execute(
                RELEASE_SCRIPT, key(userId, reservation.quotaDate()), reservation.token());
        return result == 1L || result == 2L;
    }

    private Long execute(DefaultRedisScript<Long> script, String key, String... args) {
        try {
            Long result = scriptExecutor.execute(script, key, args);
            if (result == null) {
                throw unavailable(null);
            }
            return result;
        } catch (BusinessException e) {
            throw e;
        } catch (RuntimeException e) {
            throw unavailable(e);
        }
    }

    private String key(Long userId, LocalDate quotaDate) {
        return properties.getKeyPrefix() + ":create:user:" + userId + ":date:" + quotaDate;
    }

    private long ttlSeconds(LocalDate quotaDate) {
        ZonedDateTime now = ZonedDateTime.now(clock);
        ZonedDateTime expiresAt = quotaDate.plusDays(2).atStartOfDay(properties.zoneId());
        return Math.max(60L, Duration.between(now, expiresAt).getSeconds());
    }

    private BusinessException unavailable(RuntimeException cause) {
        if (cause != null) {
            log.warn("[ResumeCreateQuota] Redis 状态转换失败: errorType={}",
                    cause.getClass().getSimpleName());
        }
        return new BusinessException(
                ResumeErrorCode.RESUME_TASK_INFRASTRUCTURE_UNAVAILABLE,
                "简历新建额度服务暂不可用，请稍后重试",
                cause);
    }

    private static DefaultRedisScript<Long> script(String source) {
        return new DefaultRedisScript<>(source, Long.class);
    }

    public record CreationReservation(LocalDate quotaDate, String token) {
    }
}
