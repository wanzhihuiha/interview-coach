package com.interviewcoach.resume.infrastructure.redis;

import java.time.Duration;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.redisson.config.SingleServerConfig;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.util.StringUtils;

/**
 * 基于 Spring Data Redis 同源连接参数手工装配 Redisson core，不改变 Lettuce 和 StringRedisTemplate。
 */
@Configuration(proxyBeanMethods = false)
public class ResumeRedissonConfiguration {

    @Bean(destroyMethod = "shutdown")
    @Lazy
    public RedissonClient resumeRedissonClient(RedisProperties redisProperties) {
        return Redisson.create(buildConfig(redisProperties));
    }

    Config buildConfig(RedisProperties redisProperties) {
        Config config = new Config();
        String scheme = redisProperties.getSsl().isEnabled() ? "rediss" : "redis";
        SingleServerConfig server = config.useSingleServer()
                .setAddress(scheme + "://" + redisProperties.getHost() + ":" + redisProperties.getPort())
                .setDatabase(redisProperties.getDatabase());
        if (StringUtils.hasText(redisProperties.getUsername())) {
            server.setUsername(redisProperties.getUsername());
        }
        if (StringUtils.hasText(redisProperties.getPassword())) {
            server.setPassword(redisProperties.getPassword());
        }
        if (StringUtils.hasText(redisProperties.getClientName())) {
            server.setClientName(redisProperties.getClientName());
        }
        setTimeouts(server, redisProperties.getConnectTimeout(), redisProperties.getTimeout());
        return config;
    }

    private void setTimeouts(SingleServerConfig server, Duration connectTimeout, Duration commandTimeout) {
        if (connectTimeout != null) {
            server.setConnectTimeout(Math.toIntExact(connectTimeout.toMillis()));
        }
        if (commandTimeout != null) {
            server.setTimeout(Math.toIntExact(commandTimeout.toMillis()));
        }
    }
}
