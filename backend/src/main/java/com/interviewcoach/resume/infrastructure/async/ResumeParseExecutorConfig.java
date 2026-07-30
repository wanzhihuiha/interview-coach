package com.interviewcoach.resume.infrastructure.async;

import com.interviewcoach.resume.infrastructure.redis.ResumeAiTaskLeaseRunner;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 简历 AI 任务使用虚拟线程逐任务执行；并发上限由提交前的分布式许可控制。
 */
@Slf4j
@Configuration
public class ResumeParseExecutorConfig {

    public static final String EXECUTOR_BEAN_NAME = "resumeParseExecutor";
    /**
     * 每个被准入的任务立即获得独立虚拟线程，不再使用固定平台线程或业务等待队列。
     */
    @Bean(name = EXECUTOR_BEAN_NAME, destroyMethod = "shutdown")
    public ExecutorService resumeParseExecutor() {
        log.info("[ResumeTask] 初始化逐任务虚拟线程执行器");
        return Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("resume-ai-", 0).factory());
    }

    /**
     * 单个平台调度线程只负责许可续期，不承载 Worker 或业务排队。
     */
    @Bean(name = ResumeAiTaskLeaseRunner.RENEW_EXECUTOR_BEAN_NAME, destroyMethod = "shutdown")
    public ScheduledExecutorService resumeAiTaskLeaseRenewExecutor() {
        return Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().daemon(true).name("resume-ai-lease-renew").factory());
    }
}
