package com.interviewcoach.resume.infrastructure.async;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

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
