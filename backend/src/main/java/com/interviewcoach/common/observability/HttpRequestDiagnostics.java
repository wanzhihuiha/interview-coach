package com.interviewcoach.common.observability;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 在单次 HTTP 请求内聚合 Controller 和 Repository 诊断信息，供结束日志一次性输出。
 *
 * <p>聚合数据存放在 {@link HttpServletRequest} 属性中，不使用全局变量；请求结束后由
 * {@link HttpTimingFilter} 读取快照，后台线程没有请求上下文时不会参与该请求的汇总。</p>
 */
public final class HttpRequestDiagnostics {

    private static final String ATTRIBUTE = HttpRequestDiagnostics.class.getName() + ".data";

    private HttpRequestDiagnostics() {
    }

    static void setHandler(HttpServletRequest request, String handler) {
        if (request != null) {
            data(request).setHandler(handler);
        }
    }

    static void setUserId(HttpServletRequest request, String userId) {
        if (request != null) {
            data(request).setUserId(userId);
        }
    }

    static void setBusinessCode(HttpServletRequest request, int businessCode) {
        if (request != null) {
            data(request).setBusinessCode(businessCode);
        }
    }

    /**
     * 供全局异常处理器补写当前请求的业务码；不存在 HTTP 请求上下文时安全忽略。
     */
    public static void setCurrentBusinessCode(int businessCode) {
        setBusinessCode(currentRequest(), businessCode);
    }

    /**
     * 累加当前请求内的 Repository 次数、总耗时和最慢方法。
     *
     * @return {@code true} 表示已计入 HTTP 请求汇总；{@code false} 表示当前是后台调用，
     *         Repository 切面据此单独输出慢调用
     */
    static boolean recordRepositoryCall(String method, long durationNanos) {
        HttpServletRequest request = currentRequest();
        if (request == null) {
            return false;
        }
        data(request).recordRepositoryCall(method, durationNanos);
        return true;
    }

    /**
     * 获取请求结束时的一致快照；尚未产生诊断数据时返回空快照，保证 finally 阶段仍可记录日志。
     */
    static Snapshot snapshot(HttpServletRequest request) {
        Object value = request.getAttribute(ATTRIBUTE);
        return value instanceof Data diagnostics ? diagnostics.snapshot() : Snapshot.EMPTY;
    }

    private static HttpServletRequest currentRequest() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            return attributes.getRequest();
        }
        return null;
    }

    private static Data data(HttpServletRequest request) {
        Object value = request.getAttribute(ATTRIBUTE);
        if (value instanceof Data diagnostics) {
            return diagnostics;
        }
        Data diagnostics = new Data();
        request.setAttribute(ATTRIBUTE, diagnostics);
        return diagnostics;
    }

    /**
     * 请求内可变聚合器。同步方法保证读取结束快照时不会看到一半更新的统计值。
     */
    private static final class Data {

        private String handler;
        private String userId;
        private Integer businessCode;
        private int repositoryCalls;
        private long repositoryDurationNanos;
        private String slowestRepository;
        private long slowestRepositoryDurationNanos;

        synchronized void setHandler(String handler) {
            this.handler = handler;
        }

        synchronized void setUserId(String userId) {
            this.userId = userId;
        }

        synchronized void setBusinessCode(int businessCode) {
            this.businessCode = businessCode;
        }

        synchronized void recordRepositoryCall(String method, long durationNanos) {
            repositoryCalls++;
            repositoryDurationNanos += durationNanos;
            if (durationNanos > slowestRepositoryDurationNanos) {
                slowestRepositoryDurationNanos = durationNanos;
                slowestRepository = method;
            }
        }

        synchronized Snapshot snapshot() {
            return new Snapshot(
                    handler,
                    userId,
                    businessCode,
                    repositoryCalls,
                    toMillis(repositoryDurationNanos),
                    slowestRepository,
                    toMillis(slowestRepositoryDurationNanos));
        }

        private double toMillis(long durationNanos) {
            return Math.round(durationNanos / 1_000.0) / 1_000.0;
        }
    }

    record Snapshot(
            String handler,
            String userId,
            Integer businessCode,
            int repositoryCalls,
            double repositoryDurationMs,
            String slowestRepository,
            double slowestRepositoryDurationMs) {

        private static final Snapshot EMPTY = new Snapshot(null, null, null, 0, 0, null, 0);
    }
}
