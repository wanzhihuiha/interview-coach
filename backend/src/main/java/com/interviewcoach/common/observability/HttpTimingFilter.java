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

    /** 上游请求标识的读取头和下游响应标识的写入头。 */
    private static final String REQUEST_ID_HEADER = "X-Request-Id";

    /**
     * 允许沿用的上游 requestId 规则：仅字母、数字、点、下划线和连字符，长度 1 至 64。
     *
     * <p>64 是当前固定上限，精确取值依据缺失；调小会拒绝更多上游标识，调大则允许更长值
     * 进入 MDC 与响应头。</p>
     */
    private static final Pattern SAFE_REQUEST_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    /**
     * 查询参数、客户端地址和 User-Agent 写入日志前的最大 UTF-16 长度。
     *
     * <p>2000 是当前固定上限，精确取值依据缺失；调小会损失更多诊断内容，调大会增加日志体积。</p>
     */
    private static final int MAX_TRANSPORT_VALUE_LENGTH = 2000;

    /**
     * 请求完成日志提升为 WARN 的耗时阈值，单位为毫秒。
     *
     * <p>配置默认值为 1000ms，精确取值依据缺失；调低会产生更多慢请求警告，调高则减少告警覆盖。</p>
     */
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

        // 在当前请求线程建立 MDC 作用域，结束后恢复线程原值，避免容器线程复用串联错误。
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
                // 继续执行安全过滤器、MVC 映射和 Controller；返回或抛错后统一进入完成汇总。
                filterChain.doFilter(request, response);
            } catch (ServletException | IOException | RuntimeException | Error e) {
                failure = e;
                throw e;
            } finally {
                // 即使下游异常也读取已聚合诊断数据并记录一次端到端结果，随后原异常继续传播。
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
        // Controller、异常处理器和 Repository 切面共同写入请求属性，此处只消费结束快照。
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

    /**
     * 优先取 {@code X-Forwarded-For} 的第一个地址，否则取 Servlet 容器的远端地址；本方法不验证
     * 代理可信边界，是否信任该请求头由部署链路负责。
     */
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

    /**
     * 归一化查询参数名并判断是否属于需要掩码的凭据字段。
     */
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
