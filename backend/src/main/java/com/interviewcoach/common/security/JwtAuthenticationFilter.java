package com.interviewcoach.common.security;

import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Spring Security 请求链中的 JWT 认证适配器。
 *
 * <p>它从 Authorization Bearer 头读取令牌，经 {@link JwtUtil} 严格校验后把用户 ID 和角色
 * 写入当前线程的安全上下文；缺失或无效令牌不会创建认证，过滤链仍继续交给后续授权规则处理。</p>
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    /** 承载 Bearer 令牌的标准 HTTP 请求头名称。 */
    private static final String AUTHORIZATION_HEADER = "Authorization";
    /** 从 Authorization 头中识别并剥离令牌的前缀，当前匹配区分大小写。 */
    private static final String BEARER_PREFIX = "Bearer ";

    /** 负责验证签名、过期时间并读取用户声明的 JWT 协作者。 */
    private final JwtUtil jwtUtil;

    public JwtAuthenticationFilter(JwtUtil jwtUtil) {
        this.jwtUtil = jwtUtil;
    }

    /**
     * 尝试建立当前请求的用户认证，并无论认证是否成功都继续后续安全过滤链。
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        // 只接受 Authorization 头中的 Bearer 值，其他格式按未携带令牌处理。
        String token = resolveToken(request);
        // 严格校验签名、结构和有效期通过后，才读取主体与角色建立服务端可信身份。
        if (StringUtils.hasText(token) && jwtUtil.validateToken(token)) {
            Long userId = jwtUtil.extractUserId(token);
            List<String> roles = extractRoles(token);
            List<SimpleGrantedAuthority> authorities = roles.stream()
                    .map(role -> new SimpleGrantedAuthority("ROLE_" + role))
                    .collect(Collectors.toList());
            UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken(userId, null, authorities);
            // 将数字用户 ID 和角色权限写入当前请求线程，供 Controller 与方法授权继续使用。
            SecurityContextHolder.getContext().setAuthentication(authentication);
        }
        // 无令牌或令牌无效时也继续，由 SecurityFilterChain 的公开路径和 authenticated 规则决定结果。
        filterChain.doFilter(request, response);
    }

    private String resolveToken(HttpServletRequest request) {
        String bearerToken = request.getHeader(AUTHORIZATION_HEADER);
        if (StringUtils.hasText(bearerToken) && bearerToken.startsWith(BEARER_PREFIX)) {
            return bearerToken.substring(BEARER_PREFIX.length());
        }
        return null;
    }

    /**
     * 读取令牌中的角色列表。
     *
     * <p>claim 是列表时原样返回，包括合法空列表；claim 不是列表或解析发生异常时才回退为
     * 单个 {@code USER}。本方法不把“列表为空”改写成默认角色。</p>
     */
    @SuppressWarnings("unchecked")
    private List<String> extractRoles(String token) {
        try {
            Claims claims = jwtUtil.parseToken(token);
            Object rolesClaim = claims.get("roles");
            if (rolesClaim instanceof List) {
                return ((List<String>) rolesClaim);
            }
        } catch (Exception e) {
            // 严格解析异常时不向过滤链传播内部原因，按缺少可用角色声明的兼容路径处理。
        }
        return Collections.singletonList("USER");
    }
}
