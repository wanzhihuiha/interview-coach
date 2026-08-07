package com.interviewcoach.common.observability;

import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 聚合 Spring Data Repository 调用耗时；正常请求只在 HTTP 结束日志输出汇总。
 * 不记录方法参数和返回值，避免实体内容进入通用持久化日志。
 */
@Slf4j
@Aspect
@Component
@Order(Ordered.LOWEST_PRECEDENCE - 100)
public class RepositoryTimingAspect {

    private final long slowThresholdMillis;

    public RepositoryTimingAspect(
            @Value("${observability.repository.slow-threshold-ms:500}") long slowThresholdMillis) {
        this.slowThresholdMillis = Math.max(slowThresholdMillis, 0);
    }

    /**
     * 统计所有 Spring Data Repository 公共调用，并保持原始返回值或异常语义不变。
     * 请求内调用写入 HTTP 聚合器；失败这里只打印可检索摘要，堆栈由上层异常边界统一记录。
     */
    @Around("execution(public * org.springframework.data.repository.Repository+.*(..))")
    public Object recordRepositoryTiming(ProceedingJoinPoint joinPoint) throws Throwable {
        String method = joinPoint.getSignature().toShortString();
        long startedAtNanos = System.nanoTime();
        try {
            Object result = joinPoint.proceed();
            recordSuccess(method, System.nanoTime() - startedAtNanos);
            return result;
        } catch (Throwable throwable) {
            long durationNanos = System.nanoTime() - startedAtNanos;
            HttpRequestDiagnostics.recordRepositoryCall(method, durationNanos);
            log.warn("[Repository] 调用失败: method={}, errorType={}, durationMs={}",
                    method,
                    throwable.getClass().getSimpleName(),
                    elapsedMillis(durationNanos));
            if (log.isDebugEnabled()) {
                log.debug("[Repository] 调用异常详情: method={}, errorMessage={}",
                        method, rootMessage(throwable));
            }
            throw throwable;
        }
    }

    /**
     * HTTP 请求内的普通调用只聚合，后台普通调用降到 TRACE；慢调用无论是否绑定请求都告警。
     */
    private void recordSuccess(String method, long durationNanos) {
        boolean requestBound = HttpRequestDiagnostics.recordRepositoryCall(method, durationNanos);
        double durationMs = elapsedMillis(durationNanos);
        if (durationMs >= slowThresholdMillis) {
            log.warn("[Repository] 慢调用: method={}, durationMs={}, requestBound={}",
                    method, durationMs, requestBound);
        } else if (log.isTraceEnabled()) {
            log.trace("[Repository] 调用完成: method={}, durationMs={}, requestBound={}",
                    method, durationMs, requestBound);
        }
    }

    private String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        for (int depth = 0; current.getCause() != null && depth < 20; depth++) {
            current = current.getCause();
        }
        String message = current.getMessage();
        if (message == null || message.isBlank()) {
            return "-";
        }
        String singleLine = message.replace("\r", "\\r").replace("\n", "\\n");
        return singleLine.length() <= 500
                ? singleLine
                : singleLine.substring(0, 500) + "...(truncated)";
    }

    private double elapsedMillis(long durationNanos) {
        return Math.round(durationNanos / 1_000.0) / 1_000.0;
    }
}
