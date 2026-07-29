package com.interviewcoach.common.observability;

import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 记录 Spring Data Repository 调用耗时，用于判断请求延迟是否主要发生在数据库访问阶段。
 *
 * <p>切面不记录方法参数、SQL 绑定值或查询结果，避免把用户与业务数据写入日志。</p>
 */
@Slf4j
@Aspect
@Component
@Order(Ordered.LOWEST_PRECEDENCE - 100)
public class RepositoryTimingAspect {

    @Around("execution(public * org.springframework.data.repository.Repository+.*(..))")
    public Object recordRepositoryTiming(ProceedingJoinPoint joinPoint) throws Throwable {
        if (!log.isDebugEnabled()) {
            return joinPoint.proceed();
        }

        String requestId = MDC.get(HttpTimingFilter.REQUEST_ID_MDC_KEY);
        String method = joinPoint.getSignature().toShortString();
        long startedAtNanos = System.nanoTime();
        log.debug("[RepositoryTiming] requestId={} phase=START method={}",
                requestId == null ? "background" : requestId,
                method);
        try {
            Object result = joinPoint.proceed();
            log.debug("[RepositoryTiming] requestId={} phase=END method={} outcome=SUCCESS durationMs={}",
                    requestId == null ? "background" : requestId,
                    method,
                    elapsedMillis(startedAtNanos));
            return result;
        } catch (Throwable throwable) {
            log.debug("[RepositoryTiming] requestId={} phase=END method={} outcome=FAILED errorType={} durationMs={}",
                    requestId == null ? "background" : requestId,
                    method,
                    throwable.getClass().getSimpleName(),
                    elapsedMillis(startedAtNanos));
            throw throwable;
        }
    }

    private double elapsedMillis(long startedAtNanos) {
        return Math.round((System.nanoTime() - startedAtNanos) / 1_000.0) / 1_000.0;
    }
}
