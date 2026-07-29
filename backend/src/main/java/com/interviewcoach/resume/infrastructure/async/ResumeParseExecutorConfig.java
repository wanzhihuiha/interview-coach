package com.interviewcoach.resume.infrastructure.async;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 简历解析任务线程池配置，限制并发和排队数量，避免集中占满 LLM 与应用资源。
 * 队列满时使用拒绝策略，由事件监听器记录失败并更新任务状态。
 */
@Slf4j
@Configuration
public class ResumeParseExecutorConfig {

    public static final String EXECUTOR_BEAN_NAME = "resumeParseExecutor";
    private static final int POOL_SIZE = 2;
    private static final int QUEUE_CAPACITY = 20;

    /**
     * 创建仅供简历解析使用的有界线程池，应用关闭时由 Spring 调用 shutdown。
     */
    @Bean(name = EXECUTOR_BEAN_NAME, destroyMethod = "shutdown")
    public ExecutorService resumeParseExecutor() {
        log.info("[ResumeParse] 初始化解析线程池: poolSize={}, queueCapacity={}, rejectionPolicy={}",
                POOL_SIZE, QUEUE_CAPACITY, ThreadPoolExecutor.AbortPolicy.class.getSimpleName());
        AtomicInteger sequence = new AtomicInteger();
        ThreadFactory threadFactory = runnable -> {
            Thread thread = new Thread(runnable, "resume-parse-" + sequence.incrementAndGet());
            thread.setDaemon(false);
            return thread;
        };
        return new ThreadPoolExecutor(
                POOL_SIZE,
                POOL_SIZE,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(QUEUE_CAPACITY),
                threadFactory,
                new ThreadPoolExecutor.AbortPolicy());
    }
}
