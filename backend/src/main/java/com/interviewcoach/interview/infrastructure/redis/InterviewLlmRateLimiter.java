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
 * 面试模块 LLM API 限流器。
 *
 * <p>基于 Redis 实现分布式令牌桶，分别限制每分钟请求数和每分钟 Token 数。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InterviewLlmRateLimiter {

    private static final String REQUEST_BUCKET_KEY = "interview:llm:rate:requests";
    private static final String TOKEN_BUCKET_KEY = "interview:llm:rate:tokens";
    private static final int WINDOW_SECONDS = 60;

    /**
     * 令牌桶脚本：原子检查并扣减令牌，支持按时间 refill。
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

    private final StringRedisTemplate redisTemplate;
    private final InterviewLlmRateLimitProperties properties;

    private DefaultRedisScript<Long> tokenBucketScript;

    @PostConstruct
    public void init() {
        tokenBucketScript = new DefaultRedisScript<>();
        tokenBucketScript.setScriptText(TOKEN_BUCKET_SCRIPT);
        tokenBucketScript.setResultType(Long.class);
    }

    /**
     * 尝试获取 LLM 调用许可。
     *
     * @param estimatedTokens 预估本次调用消耗的 Token 数
     * @return true 允许调用，false 触发限流
     */
    public boolean tryAcquire(int estimatedTokens) {
        if (!properties.isEnabled()) {
            return true;
        }

        long nowSeconds = System.currentTimeMillis() / 1000;

        // 1. 限制请求数
        boolean requestAllowed = acquireToken(REQUEST_BUCKET_KEY,
                properties.getRequestsPerMinute(), nowSeconds, 1);
        if (!requestAllowed) {
            log.warn("[LlmRateLimit] 请求数超限 {}/min", properties.getRequestsPerMinute());
            return false;
        }

        // 2. 限制 Token 数
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
     * 估算文本对应的 Token 数（中文字符按 1:1 粗略估算，实际调用后可按真实值校准）。
     */
    public int estimateTokens(String systemPrompt, String userPrompt) {
        int length = 0;
        if (systemPrompt != null) {
            length += systemPrompt.length();
        }
        if (userPrompt != null) {
            length += userPrompt.length();
        }
        // 中文约 1 字 1 token，英文约 4 字符 1 token，这里取保守估算
        return Math.max(1, length);
    }

    private boolean acquireToken(String bucketKey, int capacity, long nowSeconds, int requested) {
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
