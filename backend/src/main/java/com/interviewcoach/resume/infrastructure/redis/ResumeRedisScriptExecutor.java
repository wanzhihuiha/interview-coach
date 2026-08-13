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

    /** 使用项目现有字符串序列化执行 Lua 的 Redis 模板。 */
    private final StringRedisTemplate redisTemplate;

    /**
     * 以唯一 Key 和有序字符串参数执行返回 Long 的 Lua；空返回和 Redis 异常原样交给额度服务失败关闭。
     */
    public Long execute(DefaultRedisScript<Long> script, String key, String... args) {
        return redisTemplate.execute(script, List.of(key), (Object[]) args);
    }
}
