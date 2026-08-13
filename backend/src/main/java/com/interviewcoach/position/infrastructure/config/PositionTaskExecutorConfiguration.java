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
 * 岗位文件提取、Redis 投影、当前 JVM 串行调度和模型 Worker 的 Spring 执行器装配。
 * 各 Bean 按名称注入对应组件，关闭应用上下文时由 Spring 调用 {@code shutdown}；本配置不声明跨实例共享执行容量。
 */
@Configuration
public class PositionTaskExecutorConfiguration {

    /** JD 临时文件提取使用的有界平台线程执行器 Bean 名。 */
    public static final String FILE_EXTRACTION_EXECUTOR = "positionFileExtractionExecutor";

    /** 数据库提交后向 Redis 写队列投影使用的有界平台线程执行器 Bean 名。 */
    public static final String ENQUEUE_EXECUTOR = "positionAnalysisEnqueueExecutor";

    /** 当前 JVM 串行领取和延迟重试使用的单线程调度执行器 Bean 名。 */
    public static final String DISPATCH_EXECUTOR = "positionAnalysisDispatchExecutor";

    /** 已取得本地许可并完成数据库领取的模型任务使用的逐任务虚拟线程执行器 Bean 名。 */
    public static final String WORKER_EXECUTOR = "positionAnalysisWorkerExecutor";

    /**
     * 按上传配置创建有界提取池；队列满时拒绝提交，由文件服务保留清理责任并返回繁忙错误。
     *
     * @param properties JD 提取线程数和队列容量配置
     * @return 文件提取专用执行器
     */
    @Bean(name = FILE_EXTRACTION_EXECUTOR, destroyMethod = "shutdown")
    public ExecutorService positionFileExtractionExecutor(PositionUploadProperties properties) {
        return fixedBoundedExecutor(
                properties.getExtractionThreads(),
                properties.getExtractionQueueCapacity(),
                "position-jd-extract-");
    }

    /**
     * 按解析配置创建有界 Redis 投影池；队列满时拒绝事件提交，MySQL WAITING 事实不会因此回滚。
     *
     * @param properties 投影线程数和队列容量配置
     * @return 事务后投影专用执行器
     */
    @Bean(name = ENQUEUE_EXECUTOR, destroyMethod = "shutdown")
    public ExecutorService positionAnalysisEnqueueExecutor(PositionAnalysisProperties properties) {
        return fixedBoundedExecutor(
                properties.getEnqueueThreads(),
                properties.getEnqueueQueueCapacity(),
                "position-enqueue-");
    }

    /**
     * 为当前 JVM 创建单个平台线程，串行执行领取与短暂重试，不承载模型调用。
     *
     * @return 本地岗位领取调度执行器
     */
    @Bean(name = DISPATCH_EXECUTOR, destroyMethod = "shutdown")
    public ScheduledExecutorService positionAnalysisDispatchExecutor() {
        return Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().daemon(true).name("position-dispatch").factory());
    }

    /**
     * 执行器本身不预创建线程；调度器取得许可并完成 DB 领取后才提交虚拟线程。
     *
     * @return 岗位模型 Worker 专用虚拟线程执行器
     */
    @Bean(name = WORKER_EXECUTOR, destroyMethod = "shutdown")
    public ExecutorService positionAnalysisWorkerExecutor() {
        return Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("position-analysis-", 0).factory());
    }

    /**
     * 队列满时显式拒绝，让调用方决定清理临时文件或保留 MySQL WAITING 事实。
     *
     * @param threads 固定平台线程数
     * @param queueCapacity 本地有界队列容量
     * @param threadPrefix 工作线程名称前缀
     * @return 固定线程数、显式拒绝策略的执行器
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
