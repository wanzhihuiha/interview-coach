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
 * 认证服务单元测试。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class AuthServiceTest {

    @Autowired
    private AuthService authService;

    @Autowired
    private PasswordEncoder passwordEncoder;

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
