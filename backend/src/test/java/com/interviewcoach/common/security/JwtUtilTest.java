package com.interviewcoach.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.jsonwebtoken.JwtException;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 验证 JWT 的生成、严格校验、声明提取、过期解析和续期宽限边界。
 *
 * <p>所有令牌均由测试内存中的 {@link JwtUtil} 生成，本类不经过认证过滤器或 Spring Security 上下文。</p>
 */
class JwtUtilTest {

    /** 仅用于本类签名与解析样例的测试密钥，不对应任何环境凭据。 */
    private static final String SECRET = "test-secret-key-for-unit-test-only";

    @Test
    void shouldGenerateAndValidateToken() {
        JwtUtil jwtUtil = new JwtUtil(SECRET, 1, 60);
        String token = jwtUtil.generateToken(1L, "tester", List.of("USER"));

        assertThat(token).isNotBlank();
        assertThat(jwtUtil.validateToken(token)).isTrue();
        assertThat(jwtUtil.extractUserId(token)).isEqualTo(1L);
        assertThat(jwtUtil.getRemainingMinutes(token)).isPositive();
    }

    @Test
    void shouldAllowRefreshWithinGracePeriod() {
        // expirationDays=0 生成已过期（或刚过期）的 token，仍在 60 分钟宽限期内
        JwtUtil jwtUtil = new JwtUtil(SECRET, 0, 60);
        String token = jwtUtil.generateToken(1L, "tester", List.of("USER"));

        assertThat(jwtUtil.validateToken(token)).isFalse();
        assertThat(jwtUtil.canRefresh(token)).isTrue();
    }

    @Test
    void shouldRejectRefreshBeyondGracePeriod() {
        // expirationDays=-1 生成已过期超过 1 天的 token，超出 60 分钟宽限期
        JwtUtil jwtUtil = new JwtUtil(SECRET, -1, 60);
        String token = jwtUtil.generateToken(1L, "tester", List.of("USER"));

        assertThat(jwtUtil.canRefresh(token)).isFalse();
    }

    @Test
    void shouldParseExpiredTokenIgnoringExpiration() {
        JwtUtil jwtUtil = new JwtUtil(SECRET, 0, 60);
        String token = jwtUtil.generateToken(1L, "tester", List.of("USER"));

        assertThat(jwtUtil.parseTokenIgnoringExpiration(token).getSubject()).isEqualTo("1");
    }

    @Test
    void shouldRejectInvalidToken() {
        JwtUtil jwtUtil = new JwtUtil(SECRET, 1, 60);

        assertThat(jwtUtil.validateToken("invalid-token")).isFalse();
        assertThat(jwtUtil.canRefresh("invalid-token")).isFalse();
        assertThatThrownBy(() -> jwtUtil.parseToken("invalid-token"))
                .isInstanceOf(JwtException.class);
    }
}
