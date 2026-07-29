package com.interviewcoach.common.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 记录 API 请求的端到端耗时，用于区分代理、鉴权、业务处理和响应阶段的延迟。
 *
 * <p>日志只包含请求方法、路径、状态码和耗时，不记录查询参数、请求体、认证信息或响应内容。</p>
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class HttpTimingFilter extends OncePerRequestFilter {

    static final String REQUEST_ID_MDC_KEY = "diagnosticRequestId";

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (!log.isDebugEnabled()) {
            filterChain.doFilter(request, response);
            return;
        }

        String previousRequestId = MDC.get(REQUEST_ID_MDC_KEY);
        String requestId = UUID.randomUUID().toString().substring(0, 8);
        long startedAtNanos = System.nanoTime();
        MDC.put(REQUEST_ID_MDC_KEY, requestId);
        log.debug("[HttpTiming] requestId={} phase=START method={} path={}",
                requestId, request.getMethod(), request.getRequestURI());

        try {
            filterChain.doFilter(request, response);
        } finally {
            log.debug("[HttpTiming] requestId={} phase=END method={} path={} status={} durationMs={}",
                    requestId,
                    request.getMethod(),
                    request.getRequestURI(),
                    response.getStatus(),
                    elapsedMillis(startedAtNanos));
            restoreRequestId(previousRequestId);
        }
    }

    private double elapsedMillis(long startedAtNanos) {
        return Math.round((System.nanoTime() - startedAtNanos) / 1_000.0) / 1_000.0;
    }

    private void restoreRequestId(String previousRequestId) {
        if (previousRequestId == null) {
            MDC.remove(REQUEST_ID_MDC_KEY);
        } else {
            MDC.put(REQUEST_ID_MDC_KEY, previousRequestId);
        }
    }
}
