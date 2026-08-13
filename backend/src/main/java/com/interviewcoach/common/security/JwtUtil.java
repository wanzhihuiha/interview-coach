package com.interviewcoach.common.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 由 Spring 创建、供认证服务和 JWT 过滤器使用的令牌签发与解析组件。
 *
 * <p>它使用配置密钥验证签名和有效期，并为续期入口提供受宽限期约束的过期令牌读取；
 * 不负责从 HTTP 请求取令牌，也不决定接口授权规则。</p>
 */
@Component
public class JwtUtil {

    /**
     * 由 {@code jwt.secret} 配置派生的 HMAC 签名密钥，既用于签发也用于验证，属于敏感认证材料。
     */
    private final SecretKey secretKey;

    /**
     * 新令牌从签发时刻起的有效天数，来自 {@code jwt.expiration-days}，当前配置默认值为 7 天。
     * 调大延长令牌可用窗口，调小会让客户端更早需要续期。
     */
    private final long expirationDays;

    /**
     * 令牌过期后仍允许续期的分钟数，来自 {@code jwt.refresh-grace-minutes}，当前默认值为 60 分钟。
     *
     * <p>60 分钟的精确依据缺失；调大会延长过期令牌可换新令牌的窗口，调小会更早拒绝续期。</p>
     */
    private final long refreshGraceMinutes;

    /**
     * 从 Spring 配置创建签名密钥和两个时间窗口；密钥不足 32 字节时阻止组件完成装配。
     */
    public JwtUtil(
            @Value("${jwt.secret}") String secret,
            @Value("${jwt.expiration-days:7}") long expirationDays,
            @Value("${jwt.refresh-grace-minutes:60}") long refreshGraceMinutes) {
        if (secret == null || secret.getBytes().length < 32) {
            throw new IllegalArgumentException(
                    "JWT secret 长度必须至少 32 字节（256 位），请修改 jwt.secret 配置");
        }
        this.secretKey = Keys.hmacShaKeyFor(secret.getBytes());
        this.expirationDays = expirationDays;
        this.refreshGraceMinutes = refreshGraceMinutes;
    }

    /**
     * 为已认证用户生成带用户名、角色、签发时间和过期时间的签名 JWT。
     *
     * @param userId 写入 subject、供过滤器恢复可信身份的用户 ID
     * @param username 写入自定义 claim 的用户名
     * @param roles 写入自定义 claim、供过滤器生成角色权限的英文角色列表
     * @return 使用当前配置密钥签名的紧凑 JWT 字符串
     */
    public String generateToken(Long userId, String username, List<String> roles) {
        Instant now = Instant.now();
        Instant expiration = now.plus(expirationDays, ChronoUnit.DAYS);

        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("username", username)
                .claim("roles", roles)
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiration))
                .signWith(secretKey)
                .compact();
    }

    /**
     * 严格解析令牌并校验签名、结构和过期时间；任何校验失败都以 JWT 运行时异常结束。
     *
     * @return token 中的声明
     */
    public Claims parseToken(String token) {
        return Jwts.parser()
                .verifyWith(secretKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    /**
     * 通过严格解析读取 subject 并转换为数字用户 ID；subject 非数字时转换异常向外传播。
     */
    public Long extractUserId(String token) {
        Claims claims = parseToken(token);
        return Long.valueOf(claims.getSubject());
    }

    /**
     * 判断令牌能否通过严格解析；签名、结构、有效期或参数异常均返回 {@code false}。
     */
    public boolean validateToken(String token) {
        try {
            parseToken(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * 为续期入口读取令牌声明：仅捕获“已过期”异常并取回其中已验证的声明。
     *
     * <p>签名错误、格式错误等其他校验失败仍向外抛出，因此该方法不是跳过全部 JWT 校验。</p>
     *
     * @return token 中的声明
     */
    public Claims parseTokenIgnoringExpiration(String token) {
        try {
            return parseToken(token);
        } catch (ExpiredJwtException e) {
            return e.getClaims();
        }
    }

    /**
     * 判断 token 是否可以续期。
     *
     * <p>未过期 token 可以直接续期；已过期的 token 只要在宽限期内也可以续期，
     * 且两种情况都要求 subject 非空。签名或结构无效直接返回 {@code false}。</p>
     */
    public boolean canRefresh(String token) {
        Claims claims;
        try {
            claims = Jwts.parser()
                    .verifyWith(secretKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (ExpiredJwtException e) {
            claims = e.getClaims();
            long minutesPast = ChronoUnit.MINUTES.between(claims.getExpiration().toInstant(), Instant.now());
            if (minutesPast > refreshGraceMinutes) {
                return false;
            }
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
        return claims.getSubject() != null;
    }

    /**
     * 严格解析有效令牌，并返回当前时刻到过期时刻之间的完整分钟差。
     */
    public long getRemainingMinutes(String token) {
        Claims claims = parseToken(token);
        return ChronoUnit.MINUTES.between(Instant.now(), claims.getExpiration().toInstant());
    }
}
