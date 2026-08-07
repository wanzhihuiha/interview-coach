package com.interviewcoach.common.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * 记录 API 请求的入口和端到端结果，并通过 MDC 把同一请求内的后续日志串联起来。
 *
 * <p>过滤器早于 Spring Security 和 MVC 参数绑定执行，入口日志只能判断是否携带认证头；
 * 认证后的 userId、handler 和业务码由 Controller 切面及异常处理器补入请求诊断上下文。</p>
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class HttpTimingFilter extends OncePerRequestFilter {

    private static final String REQUEST_ID_HEADER = "X-Request-Id";
    private static final Pattern SAFE_REQUEST_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");
    private static final int MAX_TRANSPORT_VALUE_LENGTH = 2000;

    private final long slowThresholdMillis;

    public HttpTimingFilter(
            @Value("${observability.http.slow-threshold-ms:1000}") long slowThresholdMillis) {
        this.slowThresholdMillis = Math.max(slowThresholdMillis, 0);
    }

    /**
     * 只观测业务 API，避免静态资源、错误页等非接口流量污染请求日志。
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    /**
     * 建立请求 MDC，输出入口信息，并在下游成功或抛错时都记录一次端到端结束摘要。
     *
     * <p>requestId 会回写响应头，便于前端报错与服务端日志互相定位。这里不读取请求体，
     * 结构化入参由完成参数绑定后的 Controller 切面在 DEBUG 级别记录。</p>
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String requestId = resolveRequestId(request);
        long startedAtNanos = System.nanoTime();
        response.setHeader(REQUEST_ID_HEADER, requestId);

        try (DiagnosticContext.Scope ignored = DiagnosticContext.openRequest(requestId)) {
            log.info("[HTTP] 请求开始: method={}, path={}, authPresent={}, "
                            + "contentType={}, contentLength={}",
                    request.getMethod(),
                    request.getRequestURI(),
                    request.getHeader("Authorization") != null,
                    valueOrDash(request.getContentType()),
                    request.getContentLengthLong());
            if (log.isDebugEnabled()) {
                log.debug("[HTTP] 请求明细: query={}, clientIp={}, userAgent={}",
                        queryDetails(request.getQueryString()),
                        transportValue(clientIp(request)),
                        transportValue(request.getHeader("User-Agent")));
            }

            Throwable failure = null;
            try {
                filterChain.doFilter(request, response);
            } catch (ServletException | IOException | RuntimeException | Error e) {
                failure = e;
                throw e;
            } finally {
                logCompletion(request, response, startedAtNanos, failure);
            }
        }
    }

    /**
     * 汇总 HTTP 状态、业务码、处理器和 Repository 统计；异常、5xx 或慢请求提升为 WARN。
     * 下游异常在计时层只记录类型，业务异常堆栈由统一异常处理器记录，过滤链异常继续交回容器处理。
     */
    private void logCompletion(
            HttpServletRequest request,
            HttpServletResponse response,
            long startedAtNanos,
            Throwable failure) {
        double durationMs = elapsedMillis(startedAtNanos);
        HttpRequestDiagnostics.Snapshot diagnostics = HttpRequestDiagnostics.snapshot(request);
        String handler = diagnostics.handler() == null
                ? resolveHandler(request)
                : diagnostics.handler();
        String outcome = outcome(response.getStatus(), diagnostics.businessCode(), failure);
        Object[] arguments = {
                request.getMethod(),
                request.getRequestURI(),
                valueOrDash(handler),
                valueOrDash(diagnostics.userId()),
                response.getStatus(),
                diagnostics.businessCode() == null ? "-" : diagnostics.businessCode(),
                outcome,
                durationMs,
                diagnostics.repositoryCalls(),
                diagnostics.repositoryDurationMs(),
                valueOrDash(diagnostics.slowestRepository()),
                diagnostics.slowestRepositoryDurationMs(),
                failure == null ? "-" : failure.getClass().getSimpleName()
        };
        String template = "[HTTP] 请求完成: method={}, path={}, handler={}, userId={}, status={}, "
                + "businessCode={}, outcome={}, durationMs={}, dbCalls={}, dbDurationMs={}, "
                + "slowestRepository={}, slowestRepositoryMs={}, errorType={}";
        if (failure != null || response.getStatus() >= 500 || durationMs >= slowThresholdMillis) {
            log.warn(template, arguments);
        } else {
            log.info(template, arguments);
        }
    }

    /**
     * 同时保留传输层和业务层结果：HTTP 成功但业务码非 0 时仍标记为 BUSINESS_ERROR。
     */
    private String outcome(int status, Integer businessCode, Throwable failure) {
        if (failure != null || status >= 500) {
            return "SERVER_ERROR";
        }
        if (status >= 400) {
            return "HTTP_ERROR";
        }
        if (businessCode != null && businessCode != 0) {
            return "BUSINESS_ERROR";
        }
        return "SUCCESS";
    }

    /**
     * 只接受受限字符和长度的上游 requestId，避免控制字符或超长值进入 MDC 和响应头。
     */
    private String resolveRequestId(HttpServletRequest request) {
        String upstreamRequestId = request.getHeader(REQUEST_ID_HEADER);
        if (upstreamRequestId != null && SAFE_REQUEST_ID.matcher(upstreamRequestId).matches()) {
            return upstreamRequestId;
        }
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    private String resolveHandler(HttpServletRequest request) {
        Object handler = request.getAttribute(HandlerMapping.BEST_MATCHING_HANDLER_ATTRIBUTE);
        if (handler instanceof HandlerMethod handlerMethod) {
            return handlerMethod.getBeanType().getSimpleName()
                    + "." + handlerMethod.getMethod().getName();
        }
        return null;
    }

    private String clientIp(HttpServletRequest request) {
        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (forwardedFor != null && !forwardedFor.isBlank()) {
            return forwardedFor.split(",", 2)[0].trim();
        }
        return request.getRemoteAddr();
    }

    /**
     * 解析并重组查询参数后再记录；凭据字段始终掩码，解析失败时也不回退输出原始字符串。
     */
    private String queryDetails(String query) {
        if (query == null || query.isBlank()) {
            return "-";
        }
        try {
            MultiValueMap<String, String> parameters = UriComponentsBuilder.newInstance()
                    .query(query)
                    .build()
                    .getQueryParams();
            MultiValueMap<String, String> sanitized = new LinkedMultiValueMap<>();
            parameters.forEach((name, values) -> sanitized.put(
                    name,
                    isCredentialParameter(name) ? List.of("***") : values));
            return transportValue(sanitized.toString());
        } catch (IllegalArgumentException e) {
            return "<unparseable-query>";
        }
    }

    private boolean isCredentialParameter(String name) {
        String normalized = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        return normalized.equals("code")
                || normalized.equals("auth")
                || normalized.endsWith("password")
                || normalized.endsWith("token")
                || normalized.endsWith("authorization")
                || normalized.endsWith("cookie")
                || normalized.endsWith("secret")
                || normalized.endsWith("apikey")
                || normalized.endsWith("accesskey")
                || normalized.endsWith("credential")
                || normalized.endsWith("smscode")
                || normalized.endsWith("verifycode")
                || normalized.endsWith("verificationcode");
    }

    private String transportValue(String value) {
        if (value == null || value.isBlank()) {
            return "-";
        }
        String singleLine = value.replace("\r", "\\r").replace("\n", "\\n");
        return singleLine.length() <= MAX_TRANSPORT_VALUE_LENGTH
                ? singleLine
                : singleLine.substring(0, MAX_TRANSPORT_VALUE_LENGTH) + "...(truncated)";
    }

    private String valueOrDash(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }

    private double elapsedMillis(long startedAtNanos) {
        return Math.round((System.nanoTime() - startedAtNanos) / 1_000.0) / 1_000.0;
    }
}
