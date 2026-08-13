package com.interviewcoach.common.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import jakarta.servlet.DispatcherType;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * 由 Spring 启动装配加载的 HTTP 与方法级安全配置。
 *
 * <p>本类提供密码编码器、认证管理器和无状态安全过滤链：注册、登录、续期及短信入口公开，
 * 其余请求要求认证；JWT 过滤器在用户名密码过滤器之前恢复用户身份，并统一配置响应安全头。</p>
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity(prePostEnabled = true)
public class SecurityConfig {

    /** 在授权规则执行前从 Bearer 令牌恢复当前用户身份的过滤器。 */
    private final JwtAuthenticationFilter jwtAuthenticationFilter;

    public SecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
    }

    /**
     * 创建 BCrypt 密码编码器，供注册和认证服务生成及校验密码哈希。
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * 从 Spring Security 当前认证装配中取得认证管理器，供登录认证流程使用。
     */
    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }

    /**
     * 构建应用 HTTP 安全链：关闭 CSRF、禁用服务端会话、声明公开入口、要求其他请求认证，
     * 并将 JWT 过滤器置于用户名密码过滤器之前。
     *
     * @return Spring Security 用于处理所有 HTTP 请求的过滤链
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(HttpMethod.POST, "/api/v1/users/register").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/v1/users/login").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/v1/users/refresh-token").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/v1/users/login/phone").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/v1/users/login/wechat").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/v1/users/sms/send").permitAll()
                .requestMatchers("/error").permitAll()
                .dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR).permitAll()
                .anyRequest().authenticated()
            )
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
            .headers(headers -> headers
                .frameOptions(frameOptions -> frameOptions.deny())
                .contentSecurityPolicy(csp -> csp.policyDirectives("default-src 'self'"))
                .contentTypeOptions(contentTypeOptions -> {})
                // HSTS 当前固定为 31536000 秒并包含子域；精确取值依据缺失，修改会改变浏览器强制 HTTPS 时长。
                .httpStrictTransportSecurity(hsts -> hsts.includeSubDomains(true).maxAgeInSeconds(31536000))
            );

        return http.build();
    }
}
