package com.interviewcoach.resume.infrastructure.redis;

import com.interviewcoach.common.exception.BusinessException;
import com.interviewcoach.resume.application.event.ResumeAiQuotaReservation;
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
 * 手动 AI 任务按上海准入日期维护 5 次成功、10 次尝试的 Lua 原子状态机。
 */
@Slf4j
@Component
public class ResumeAiQuotaService {

    private static final DefaultRedisScript<Long> RESERVE_SCRIPT = script("""
            local tokenField = 'token:' .. ARGV[1]
            if redis.call('HEXISTS', KEYS[1], tokenField) == 1 then return 2 end
            local success = tonumber(redis.call('HGET', KEYS[1], 'success') or '0')
            local successReserved = tonumber(redis.call('HGET', KEYS[1], 'successReserved') or '0')
            local attempt = tonumber(redis.call('HGET', KEYS[1], 'attempt') or '0')
            local attemptReserved = tonumber(redis.call('HGET', KEYS[1], 'attemptReserved') or '0')
            if success + successReserved >= tonumber(ARGV[2]) then return -1 end
            if attempt + attemptReserved >= tonumber(ARGV[3]) then return -2 end
            redis.call('HINCRBY', KEYS[1], 'successReserved', 1)
            redis.call('HINCRBY', KEYS[1], 'attemptReserved', 1)
            redis.call('HSET', KEYS[1], tokenField, 'RESERVED')
            redis.call('EXPIRE', KEYS[1], tonumber(ARGV[4]))
            return 1
            """);

    private static final DefaultRedisScript<Long> START_SCRIPT = script("""
            local tokenField = 'token:' .. ARGV[1]
            local state = redis.call('HGET', KEYS[1], tokenField)
            if state == 'STARTED' or state == 'SUCCEEDED' then return 2 end
            if state ~= 'RESERVED' then return 0 end
            redis.call('HINCRBY', KEYS[1], 'attemptReserved', -1)
            redis.call('HINCRBY', KEYS[1], 'attempt', 1)
            redis.call('HSET', KEYS[1], tokenField, 'STARTED')
            return 1
            """);

    private static final DefaultRedisScript<Long> SUCCESS_SCRIPT = script("""
            local tokenField = 'token:' .. ARGV[1]
            local state = redis.call('HGET', KEYS[1], tokenField)
            if state == 'SUCCEEDED' then return 2 end
            if state ~= 'STARTED' then return 0 end
            redis.call('HINCRBY', KEYS[1], 'successReserved', -1)
            redis.call('HINCRBY', KEYS[1], 'success', 1)
            redis.call('HSET', KEYS[1], tokenField, 'SUCCEEDED')
            return 1
            """);

    private static final DefaultRedisScript<Long> FAIL_SCRIPT = script("""
            local tokenField = 'token:' .. ARGV[1]
            local state = redis.call('HGET', KEYS[1], tokenField)
            if state == 'FAILED' or state == 'FAILED_BEFORE_START' then return 2 end
            if state == 'RESERVED' then
                redis.call('HINCRBY', KEYS[1], 'successReserved', -1)
                redis.call('HINCRBY', KEYS[1], 'attemptReserved', -1)
                redis.call('HSET', KEYS[1], tokenField, 'FAILED_BEFORE_START')
                return 1
            end
            if state == 'STARTED' then
                redis.call('HINCRBY', KEYS[1], 'successReserved', -1)
                redis.call('HSET', KEYS[1], tokenField, 'FAILED')
                return 1
            end
            return 0
            """);

    private final ResumeRedisScriptExecutor scriptExecutor;
    private final ResumeAiTaskProperties properties;
    private final Clock clock;

    @Autowired
    public ResumeAiQuotaService(
            ResumeRedisScriptExecutor scriptExecutor, ResumeAiTaskProperties properties) {
        this(scriptExecutor, properties, Clock.system(properties.zoneId()));
    }

    ResumeAiQuotaService(
            ResumeRedisScriptExecutor scriptExecutor, ResumeAiTaskProperties properties, Clock clock) {
        this.scriptExecutor = scriptExecutor;
        this.properties = properties;
        this.clock = clock;
    }

    public ResumeAiQuotaReservation reserve(Long userId) {
        LocalDate quotaDate = LocalDate.now(clock);
        ResumeAiQuotaReservation reservation = new ResumeAiQuotaReservation(
                quotaDate, UUID.randomUUID().toString().replace("-", ""));
        Long result = execute(
                RESERVE_SCRIPT,
                key(userId, quotaDate),
                reservation.quotaToken(),
                Integer.toString(properties.getSuccessLimit()),
                Integer.toString(properties.getAttemptLimit()),
                Long.toString(ttlSeconds(quotaDate)));
        if (result == -1L) {
            throw new BusinessException(ResumeErrorCode.AI_DAILY_SUCCESS_LIMIT, "今日 AI 任务成功名额已用完");
        }
        if (result == -2L) {
            throw new BusinessException(ResumeErrorCode.AI_DAILY_ATTEMPT_LIMIT, "今日 AI 任务尝试次数已用完");
        }
        if (result != 1L && result != 2L) {
            throw unavailable(null);
        }
        return reservation;
    }

    public boolean markAttemptStarted(Long userId, ResumeAiQuotaReservation reservation) {
        return transition(START_SCRIPT, userId, reservation);
    }

    public boolean markSucceeded(Long userId, ResumeAiQuotaReservation reservation) {
        return transition(SUCCESS_SCRIPT, userId, reservation);
    }

    public boolean markFailed(Long userId, ResumeAiQuotaReservation reservation) {
        if (reservation == null) {
            return true;
        }
        return transition(FAIL_SCRIPT, userId, reservation);
    }

    /**
     * 配额只约束准入时所属的上海自然日；日期结束后旧 token 不再影响任何新日额度。
     */
    public boolean isQuotaDateClosed(ResumeAiQuotaReservation reservation) {
        return reservation.quotaDate().isBefore(LocalDate.now(clock));
    }

    private boolean transition(
            DefaultRedisScript<Long> script, Long userId, ResumeAiQuotaReservation reservation) {
        Long result = execute(script, key(userId, reservation.quotaDate()), reservation.quotaToken());
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
        return properties.getKeyPrefix() + ":quota:user:" + userId + ":date:" + quotaDate;
    }

    private long ttlSeconds(LocalDate quotaDate) {
        ZonedDateTime now = ZonedDateTime.now(clock);
        ZonedDateTime expiresAt = quotaDate.plusDays(2).atStartOfDay(properties.zoneId());
        return Math.max(60L, Duration.between(now, expiresAt).getSeconds());
    }

    private BusinessException unavailable(RuntimeException cause) {
        if (cause != null) {
            log.warn("[ResumeAiQuota] Redis 状态转换失败: errorType={}", cause.getClass().getSimpleName());
        }
        return new BusinessException(
                ResumeErrorCode.RESUME_TASK_INFRASTRUCTURE_UNAVAILABLE,
                "AI 任务额度服务暂不可用，请稍后重试",
                cause);
    }

    private static DefaultRedisScript<Long> script(String source) {
        return new DefaultRedisScript<>(source, Long.class);
    }
}
