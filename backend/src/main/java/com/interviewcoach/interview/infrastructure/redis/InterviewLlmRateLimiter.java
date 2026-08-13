package com.interviewcoach.interview.infrastructure.redis;

import com.interviewcoach.interview.infrastructure.config.InterviewLlmRateLimitProperties;
import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/**
 * 面试 LLM 调用的 Redis 双令牌桶限流器。
 *
 * <p>{@code LlmInterviewService} 在真正传输前先调用本组件：请求桶和估算 Token 桶分别使用
 * Lua 原子补充/扣减，并共享 60 秒窗口。两个桶按顺序独立扣减，不构成跨桶原子操作；请求桶
 * 成功而 Token 桶失败时不会补回已扣请求令牌。Redis 空结果或异常会使调用拒绝或向上失败。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InterviewLlmRateLimiter {

    /** 所有面试 LLM 调用共享的请求数桶 Key，不区分用户或面试。 */
    private static final String REQUEST_BUCKET_KEY = "interview:llm:rate:requests";
    /** 所有面试 LLM 调用共享的估算 Token 桶 Key，不区分用户或面试。 */
    private static final String TOKEN_BUCKET_KEY = "interview:llm:rate:tokens";
    /** 固定补充窗口秒数；配置字段以“每分钟”表达，60 的来源可由该单位核验。 */
    private static final int WINDOW_SECONDS = 60;

    /**
     * 单个 Redis Key 的令牌桶脚本：按已过秒数线性补充，在容量足够时扣减 requested，并让
     * 令牌值与 last 时间 Key 保留两个窗口。脚本只保证单桶原子性。
     */
    private static final String TOKEN_BUCKET_SCRIPT = """
            local bucketKey = KEYS[1]
            local capacity = tonumber(ARGV[1])
            local windowSeconds = tonumber(ARGV[2])
            local now = tonumber(ARGV[3])
            local requested = tonumber(ARGV[4])

            local tokens = redis.call('GET', bucketKey)
            local lastUpdate = redis.call('GET', bucketKey .. ':last')

            if not tokens then
                tokens = capacity
            else
                tokens = tonumber(tokens)
            end

            if lastUpdate then
                local elapsed = now - tonumber(lastUpdate)
                tokens = math.min(capacity, tokens + elapsed * capacity / windowSeconds)
            end

            if tokens >= requested then
                tokens = tokens - requested
                redis.call('SET', bucketKey, tokens)
                redis.call('SET', bucketKey .. ':last', now)
                redis.call('EXPIRE', bucketKey, windowSeconds * 2)
                redis.call('EXPIRE', bucketKey .. ':last', windowSeconds * 2)
                return 1
            else
                redis.call('SET', bucketKey, tokens)
                redis.call('SET', bucketKey .. ':last', now)
                redis.call('EXPIRE', bucketKey, windowSeconds * 2)
                redis.call('EXPIRE', bucketKey .. ':last', windowSeconds * 2)
                return 0
            end
            """;

    /** 负责执行 Lua 并读写共享 Redis Key 空间。 */
    private final StringRedisTemplate redisTemplate;
    /** 提供启用开关、请求桶容量和估算 Token 桶容量。 */
    private final InterviewLlmRateLimitProperties properties;

    /** 初始化后可复用的 Long 返回值令牌桶脚本对象。 */
    private DefaultRedisScript<Long> tokenBucketScript;

    /** Spring 依赖注入后编译脚本文本并声明返回 Long，供后续两个桶复用。 */
    @PostConstruct
    public void init() {
        tokenBucketScript = new DefaultRedisScript<>();
        tokenBucketScript.setScriptText(TOKEN_BUCKET_SCRIPT);
        tokenBucketScript.setResultType(Long.class);
    }

    /**
     * 按请求桶、Token 桶的固定顺序尝试取得一次 LLM 调用许可。
     *
     * @param estimatedTokens 预估本次调用消耗的 Token 数
     * @return 两个桶均扣减成功时为 true；任一桶不足或脚本返回空时为 false
     */
    public boolean tryAcquire(int estimatedTokens) {
        if (!properties.isEnabled()) {
            return true;
        }

        long nowSeconds = System.currentTimeMillis() / 1000;

        // 先扣一次请求额度；后续 Token 桶失败时当前实现不补偿该次请求额度。
        boolean requestAllowed = acquireToken(REQUEST_BUCKET_KEY,
                properties.getRequestsPerMinute(), nowSeconds, 1);
        if (!requestAllowed) {
            log.warn("[LlmRateLimit] 请求数超限 {}/min", properties.getRequestsPerMinute());
            return false;
        }

        // 再按至少 1 的估算成本扣 Token 额度；两个 Lua 调用不是同一原子事务。
        int tokenCost = Math.max(1, estimatedTokens);
        boolean tokenAllowed = acquireToken(TOKEN_BUCKET_KEY,
                properties.getTokensPerMinute(), nowSeconds, tokenCost);
        if (!tokenAllowed) {
            log.warn("[LlmRateLimit] Token 数超限 {}/min", properties.getTokensPerMinute());
            return false;
        }

        return true;
    }

    /**
     * 将 system/user prompt 的 UTF-16 长度之和作为 Token 粗估，最少返回 1。
     * 该值没有按中英文分别换算，也不会用 provider 实际用量回写，只用于当前限流扣减。
     */
    public int estimateTokens(String systemPrompt, String userPrompt) {
        int length = 0;
        if (systemPrompt != null) {
            length += systemPrompt.length();
        }
        if (userPrompt != null) {
            length += userPrompt.length();
        }
        // 当前实现直接按字符单元总数估算，不声明对具体模型一定保守或精确。
        return Math.max(1, length);
    }

    /**
     * 对指定桶执行一次 Lua；仅返回值 1 表示成功，0、null 或其他值均视为拒绝。
     */
    private boolean acquireToken(String bucketKey, int capacity, long nowSeconds, int requested) {
        // 脚本在 Redis 服务端原子读写该桶及其 last 时间 Key，异常由模板向调用方传播。
        Long result = redisTemplate.execute(
                tokenBucketScript,
                List.of(bucketKey),
                String.valueOf(capacity),
                String.valueOf(WINDOW_SECONDS),
                String.valueOf(nowSeconds),
                String.valueOf(requested));
        return result != null && result == 1L;
    }
}
