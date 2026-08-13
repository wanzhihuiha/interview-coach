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
 * 手动 AI 任务按注入时钟计算的准入日期维护 Redis Lua token 状态机；成功与尝试上限来自任务配置。
 */
@Slf4j
@Component
public class ResumeAiQuotaService {

    /** 原子预留成功名额和尝试名额，并把新 token 记为 RESERVED；达到配置上限时返回拒绝码。 */
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

    /** 将 RESERVED 幂等推进为 STARTED，把预留尝试转为已实际开始的模型调用次数。 */
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

    /** 将 STARTED 幂等推进为 SUCCEEDED，把预留成功名额转为成功计数。 */
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

    /** 按当前 token 状态幂等失败：未开始时返还两类预留，已开始时只返还成功预留。 */
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

    /** 通过项目现有 StringRedisTemplate 执行单 Key Lua。 */
    private final ResumeRedisScriptExecutor scriptExecutor;
    /** 提供上限、Key 前缀、时区及 TTL 计算参数。 */
    private final ResumeAiTaskProperties properties;
    /** 计算准入日期和关闭日期的可注入时钟。 */
    private final Clock clock;

    /** 使用配置时区创建系统时钟的 Spring 注入入口。 */
    @Autowired
    public ResumeAiQuotaService(
            ResumeRedisScriptExecutor scriptExecutor, ResumeAiTaskProperties properties) {
        this(scriptExecutor, properties, Clock.system(properties.zoneId()));
    }

    /** 允许测试或调用方注入确定性时钟，不改变生产时区规则。 */
    ResumeAiQuotaService(
            ResumeRedisScriptExecutor scriptExecutor, ResumeAiTaskProperties properties, Clock clock) {
        this.scriptExecutor = scriptExecutor;
        this.properties = properties;
        this.clock = clock;
    }

    /** 为用户在当前额度日期预留一次成功名额和一次尝试名额；Redis 异常时失败关闭。 */
    public ResumeAiQuotaReservation reserve(Long userId) {
        LocalDate quotaDate = LocalDate.now(clock);
        ResumeAiQuotaReservation reservation = new ResumeAiQuotaReservation(
                quotaDate, UUID.randomUUID().toString().replace("-", ""));
        // 单次 Lua 同时检查配置上限、增加预留并保存 token，避免拆分命令产生竞态。
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

    /** 在真正调用模型前把 token 从 RESERVED 推进为 STARTED；状态不匹配时返回 false。 */
    public boolean markAttemptStarted(Long userId, ResumeAiQuotaReservation reservation) {
        return transition(START_SCRIPT, userId, reservation);
    }

    /** 数据库成功终态确认后把 token 从 STARTED 推进为 SUCCEEDED。 */
    public boolean markSucceeded(Long userId, ResumeAiQuotaReservation reservation) {
        return transition(SUCCESS_SCRIPT, userId, reservation);
    }

    /** 数据库失败终态确认后释放尚未消耗的预留；免费任务没有 reservation 时直接视为完成。 */
    public boolean markFailed(Long userId, ResumeAiQuotaReservation reservation) {
        if (reservation == null) {
            return true;
        }
        return transition(FAIL_SCRIPT, userId, reservation);
    }

    /**
     * 配额只约束准入时按注入时钟确定的自然日；该日期结束后旧 token 不再影响任何新日额度。
     */
    public boolean isQuotaDateClosed(ResumeAiQuotaReservation reservation) {
        return reservation.quotaDate().isBefore(LocalDate.now(clock));
    }

    /** 使用 reservation 固定的准入日期定位 Key，并把脚本成功或幂等结果统一为 true。 */
    private boolean transition(
            DefaultRedisScript<Long> script, Long userId, ResumeAiQuotaReservation reservation) {
        Long result = execute(script, key(userId, reservation.quotaDate()), reservation.quotaToken());
        return result == 1L || result == 2L;
    }

    /** 执行 Lua 并将空返回或 Redis 异常映射为任务基础设施失败，避免额度状态未知时放行。 */
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

    /** 构造按用户和准入日期隔离的额度 Key。 */
    private String key(Long userId, LocalDate quotaDate) {
        return properties.getKeyPrefix() + ":quota:user:" + userId + ":date:" + quotaDate;
    }

    /**
     * 计算保留到准入日期后第二天结束的 TTL，且至少 60 秒；两天保留和最短 TTL 的精确依据缺失。
     */
    private long ttlSeconds(LocalDate quotaDate) {
        ZonedDateTime now = ZonedDateTime.now(clock);
        ZonedDateTime expiresAt = quotaDate.plusDays(2).atStartOfDay(properties.zoneId());
        return Math.max(60L, Duration.between(now, expiresAt).getSeconds());
    }

    /** 将 Redis 故障映射为安全的失败关闭业务错误，不记录 Key、token 或用户数据。 */
    private BusinessException unavailable(RuntimeException cause) {
        if (cause != null) {
            log.warn("[ResumeAiQuota] Redis 状态转换失败: errorType={}", cause.getClass().getSimpleName());
        }
        return new BusinessException(
                ResumeErrorCode.RESUME_TASK_INFRASTRUCTURE_UNAVAILABLE,
                "AI 任务额度服务暂不可用，请稍后重试",
                cause);
    }

    /** 创建返回 Long 的 Redis Lua 脚本描述。 */
    private static DefaultRedisScript<Long> script(String source) {
        return new DefaultRedisScript<>(source, Long.class);
    }
}
