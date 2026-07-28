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
 * JWT 工具类，负责 token 生成与校验。
 */
@Component
public class JwtUtil {

    private final SecretKey secretKey;
    private final long expirationDays;
    private final long refreshGraceMinutes;

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
     * 生成 JWT token。
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
     * 解析并校验 token。
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
     * 从 token 中提取用户ID。
     */
    public Long extractUserId(String token) {
        Claims claims = parseToken(token);
        return Long.valueOf(claims.getSubject());
    }

    /**
     * 校验 token 是否有效。
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
     * 解析 token，忽略过期时间（用于续期时读取已过期 token 中的用户信息）。
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
     * 避免面试等长会话因 token 过期而中断。</p>
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
     * 获取 token 剩余有效分钟数。
     */
    public long getRemainingMinutes(String token) {
        Claims claims = parseToken(token);
        return ChronoUnit.MINUTES.between(Instant.now(), claims.getExpiration().toInstant());
    }
}
