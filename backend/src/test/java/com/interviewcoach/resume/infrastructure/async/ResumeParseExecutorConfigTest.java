package com.interviewcoach.resume.infrastructure.async;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

/**
 * 验证简历解析执行器配置创建逐任务虚拟线程 Worker 执行器，并可按 ExecutorService 契约关闭。
 *
 * <p>本类直接创建和关闭测试执行器，不启动 Spring 容器，也不覆盖独立的平台线程租约续期调度器。</p>
 */
class ResumeParseExecutorConfigTest {

    @Test
    void shouldRunEachTaskOnVirtualThread() throws Exception {
        ExecutorService executor = new ResumeParseExecutorConfig().resumeParseExecutor();
        try {
            Future<Boolean> virtual = executor.submit(() -> Thread.currentThread().isVirtual());
            assertThat(virtual.get()).isTrue();
        } finally {
            executor.shutdownNow();
        }
    }
}
