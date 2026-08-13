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
 * 按注入时钟计算的额度日期，以 Redis Lua 预留、确认或释放简历创建次数；每日上限来自任务配置。
 */
@Slf4j
@Component
public class ResumeDailyCreationQuotaService {

    /** 原子检查配置上限并把新 token 记为 RESERVED，同时增加预留数。 */
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

    /** 将 RESERVED 幂等推进为 CREATED，把预留数转为已创建数。 */
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

    /** 将 RESERVED 或 CREATED 幂等推进为 RELEASED，并返还对应计数；普通删除不会调用此脚本。 */
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

    /** 通过项目现有 StringRedisTemplate 执行单 Key Lua。 */
    private final ResumeRedisScriptExecutor scriptExecutor;
    /** 提供每日上限、Key 前缀和额度时区。 */
    private final ResumeAiTaskProperties properties;
    /** 计算准入日期和 TTL 的可注入时钟。 */
    private final Clock clock;

    /** 使用配置时区创建系统时钟的 Spring 注入入口。 */
    @Autowired
    public ResumeDailyCreationQuotaService(
            ResumeRedisScriptExecutor scriptExecutor, ResumeAiTaskProperties properties) {
        this(scriptExecutor, properties, Clock.system(properties.zoneId()));
    }

    /** 允许测试或调用方注入确定性时钟，不改变生产日期规则。 */
    ResumeDailyCreationQuotaService(
            ResumeRedisScriptExecutor scriptExecutor, ResumeAiTaskProperties properties, Clock clock) {
        this.scriptExecutor = scriptExecutor;
        this.properties = properties;
        this.clock = clock;
    }

    /** 为用户在当前额度日期预留一次创建；达到配置上限或 Redis 异常时失败关闭。 */
    public CreationReservation reserve(Long userId) {
        LocalDate quotaDate = LocalDate.now(clock);
        CreationReservation reservation = new CreationReservation(
                quotaDate, UUID.randomUUID().toString().replace("-", ""));
        // 单次 Lua 同时检查配置上限、增加预留并保存 token，避免拆分命令产生竞态。
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

    /** 数据库简历记录已确认保留后，把创建 token 从 RESERVED 推进为 CREATED。 */
    public boolean commit(Long userId, CreationReservation reservation) {
        Long result = execute(COMMIT_SCRIPT, key(userId, reservation.quotaDate()), reservation.token());
        return result == 1L || result == 2L;
    }

    /** 上传补偿确认数据库记录不存在后返还本次预留或已创建计数；普通删除不调用。 */
    public boolean release(Long userId, CreationReservation reservation) {
        if (reservation == null) {
            return true;
        }
        Long result = execute(
                RELEASE_SCRIPT, key(userId, reservation.quotaDate()), reservation.token());
        return result == 1L || result == 2L;
    }

    /** 执行 Lua 并将空返回或 Redis 异常映射为基础设施失败，避免额度状态未知时放行。 */
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

    /** 构造按用户和创建日期隔离的额度 Key。 */
    private String key(Long userId, LocalDate quotaDate) {
        return properties.getKeyPrefix() + ":create:user:" + userId + ":date:" + quotaDate;
    }

    /**
     * 计算保留到创建日期后第二天结束的 TTL，且至少 60 秒；两天保留和最短 TTL 的精确依据缺失。
     */
    private long ttlSeconds(LocalDate quotaDate) {
        ZonedDateTime now = ZonedDateTime.now(clock);
        ZonedDateTime expiresAt = quotaDate.plusDays(2).atStartOfDay(properties.zoneId());
        return Math.max(60L, Duration.between(now, expiresAt).getSeconds());
    }

    /** 将 Redis 故障映射为安全的失败关闭业务错误，不记录 Key 或 token。 */
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

    /** 创建返回 Long 的 Redis Lua 脚本描述。 */
    private static DefaultRedisScript<Long> script(String source) {
        return new DefaultRedisScript<>(source, Long.class);
    }

    /**
     * 一次创建额度预留的恢复凭据。
     *
     * @param quotaDate 准入时按注入时钟计算的日期，后续提交或释放始终使用该日期
     * @param token Redis 状态机中标识本次创建的随机凭据
     */
    public record CreationReservation(LocalDate quotaDate, String token) {
    }
}
