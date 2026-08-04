package com.interviewcoach.position.infrastructure.config;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 岗位文件提取、入队、单线程调度和虚拟线程 Worker 均使用独立执行器。
 */
@Configuration
public class PositionTaskExecutorConfiguration {

    public static final String FILE_EXTRACTION_EXECUTOR = "positionFileExtractionExecutor";
    public static final String ENQUEUE_EXECUTOR = "positionAnalysisEnqueueExecutor";
    public static final String DISPATCH_EXECUTOR = "positionAnalysisDispatchExecutor";
    public static final String WORKER_EXECUTOR = "positionAnalysisWorkerExecutor";

    @Bean(name = FILE_EXTRACTION_EXECUTOR, destroyMethod = "shutdown")
    public ExecutorService positionFileExtractionExecutor(PositionUploadProperties properties) {
        return fixedBoundedExecutor(
                properties.getExtractionThreads(),
                properties.getExtractionQueueCapacity(),
                "position-jd-extract-");
    }

    @Bean(name = ENQUEUE_EXECUTOR, destroyMethod = "shutdown")
    public ExecutorService positionAnalysisEnqueueExecutor(PositionAnalysisProperties properties) {
        return fixedBoundedExecutor(
                properties.getEnqueueThreads(),
                properties.getEnqueueQueueCapacity(),
                "position-enqueue-");
    }

    /**
     * 单个平台线程串行执行领取与短暂重试，不承载模型调用。
     */
    @Bean(name = DISPATCH_EXECUTOR, destroyMethod = "shutdown")
    public ScheduledExecutorService positionAnalysisDispatchExecutor() {
        return Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().daemon(true).name("position-dispatch").factory());
    }

    /**
     * 执行器本身不预创建线程；调度器取得许可并完成 DB 领取后才提交虚拟线程。
     */
    @Bean(name = WORKER_EXECUTOR, destroyMethod = "shutdown")
    public ExecutorService positionAnalysisWorkerExecutor() {
        return Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("position-analysis-", 0).factory());
    }

    /**
     * 队列满时显式拒绝，让调用方决定清理临时文件或保留 MySQL WAITING 事实。
     */
    private ExecutorService fixedBoundedExecutor(int threads, int queueCapacity, String threadPrefix) {
        return new ThreadPoolExecutor(
                threads,
                threads,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity),
                Thread.ofPlatform().daemon(true).name(threadPrefix, 0).factory(),
                new ThreadPoolExecutor.AbortPolicy());
    }
}
