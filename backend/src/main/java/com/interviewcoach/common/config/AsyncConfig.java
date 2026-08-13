package com.interviewcoach.common.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * 由 Spring 启动装配加载的异步方法开关配置。
 *
 * <p>{@link EnableAsync} 使审计日志存储等 Bean 上的 {@code @Async} 方法可通过 Spring 代理异步执行。
 * 本配置不声明专用执行器，也不提供任务持久化或必达保证，实际线程与失败语义由 Spring 默认配置
 * 和各异步调用方共同决定。</p>
 */
@Configuration
@EnableAsync
public class AsyncConfig {
}
