package com.interviewcoach.resume.infrastructure.redis;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/**
 * 统一使用 StringRedisTemplate 执行单 Key Lua；不改变项目现有 Redis 客户端与序列化。
 */
@Component
@RequiredArgsConstructor
public class ResumeRedisScriptExecutor {

    private final StringRedisTemplate redisTemplate;

    public Long execute(DefaultRedisScript<Long> script, String key, String... args) {
        return redisTemplate.execute(script, List.of(key), (Object[]) args);
    }
}
