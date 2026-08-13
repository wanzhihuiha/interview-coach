package com.interviewcoach.user.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.interviewcoach.common.exception.BusinessException;
import com.interviewcoach.common.security.JwtUtil;
import com.interviewcoach.user.application.dto.LoginResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * 验证 test profile 下用户名注册、登录、错误码和 JWT 返回的认证服务集成边界。
 *
 * <p>本类使用 Spring 容器中的真实服务与 H2 测试数据源，并在事务结束后回滚；它不是隔离协作者的 Mock 单元测试。</p>
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class AuthServiceTest {

    /** 执行注册和登录用例的 Spring 被测服务。 */
    @Autowired
    private AuthService authService;

    /** 认证链使用的真实密码编码 Bean；当前断言不直接调用，但容器必须能完成其装配。 */
    @Autowired
    private PasswordEncoder passwordEncoder;

    /** 校验登录响应令牌确由当前 test profile JWT 配置生成的工具 Bean。 */
    @Autowired
    private JwtUtil jwtUtil;

    @Test
    void shouldRegisterUserSuccessfully() {
        LoginResponse response = authService.registerByUsername("testuser", "Password123", "Password123", null, null);

        assertThat(response).isNotNull();
        assertThat(response.getUser()).isNotNull();
        assertThat(response.getUser().getUserId()).isNotNull();
        assertThat(response.getUser().getUsername()).isEqualTo("testuser");
        assertThat(response.getToken()).isNotBlank();
    }

    @Test
    void shouldThrowExceptionWhenUsernameAlreadyExists() {
        authService.registerByUsername("testuser", "Password123", "Password123", null, null);

        assertThatThrownBy(() -> authService.registerByUsername("testuser", "Password123", "Password123", null, null))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(UserErrorCode.USERNAME_ALREADY_EXISTS));
    }

    @Test
    void shouldThrowExceptionWhenPasswordNotMatch() {
        assertThatThrownBy(() -> authService.registerByUsername("testuser", "Password123", "Password456", null, null))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(UserErrorCode.PASSWORD_NOT_MATCH));
    }

    @Test
    void shouldLoginSuccessfully() {
        authService.registerByUsername("testuser", "Password123", "Password123", null, null);

        LoginResponse response = authService.loginByUsername("testuser", "Password123");

        assertThat(response).isNotNull();
        assertThat(response.getToken()).isNotBlank();
        assertThat(response.getUser()).isNotNull();
        assertThat(response.getUser().getUserId()).isNotNull();
        assertThat(response.getUser().getUsername()).isEqualTo("testuser");
        assertThat(jwtUtil.validateToken(response.getToken())).isTrue();
    }

    @Test
    void shouldThrowExceptionWhenPasswordError() {
        authService.registerByUsername("testuser", "Password123", "Password123", null, null);

        assertThatThrownBy(() -> authService.loginByUsername("testuser", "WrongPassword"))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(UserErrorCode.PASSWORD_ERROR));
    }
}
