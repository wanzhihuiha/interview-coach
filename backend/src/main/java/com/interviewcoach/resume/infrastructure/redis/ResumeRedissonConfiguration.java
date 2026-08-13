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
 * 基于 Spring Data Redis 同源连接参数装配 Redisson 单节点客户端，供许可和用户变更锁使用，不替换现有 Lettuce 模板。
 */
@Configuration(proxyBeanMethods = false)
public class ResumeRedissonConfiguration {

    /** 延迟创建并在 Spring 容器关闭时停止 Redisson 客户端。 */
    @Bean(destroyMethod = "shutdown")
    @Lazy
    public RedissonClient resumeRedissonClient(RedisProperties redisProperties) {
        // 将 Spring Redis 的地址、认证、数据库和超时映射为 Redisson 配置后创建客户端。
        return Redisson.create(buildConfig(redisProperties));
    }

    /** 根据 Spring Redis 属性构建单节点 Redisson 配置；生产实际拓扑与持久化策略无法由此确认。 */
    Config buildConfig(RedisProperties redisProperties) {
        Config config = new Config();
        String scheme = redisProperties.getSsl().isEnabled() ? "rediss" : "redis";
        SingleServerConfig server = config.useSingleServer()
                .setAddress(scheme + "://" + redisProperties.getHost() + ":" + redisProperties.getPort())
                .setDatabase(redisProperties.getDatabase());
        if (StringUtils.hasText(redisProperties.getUsername())) {
            // 仅把认证值传给 Redisson，不记录或复制到其他配置位置。
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

    /** 将非空连接和命令超时从 Duration 转换为 Redisson 所需毫秒整数。 */
    private void setTimeouts(SingleServerConfig server, Duration connectTimeout, Duration commandTimeout) {
        if (connectTimeout != null) {
            server.setConnectTimeout(Math.toIntExact(connectTimeout.toMillis()));
        }
        if (commandTimeout != null) {
            server.setTimeout(Math.toIntExact(commandTimeout.toMillis()));
        }
    }
}
