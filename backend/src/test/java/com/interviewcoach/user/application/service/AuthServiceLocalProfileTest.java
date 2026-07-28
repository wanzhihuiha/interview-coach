package com.interviewcoach.user.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.interviewcoach.user.application.dto.LoginResponse;
import com.interviewcoach.user.domain.entity.User;
import com.interviewcoach.user.domain.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * Local profile 下 H2 文件数据库验证测试。
 */
@SpringBootTest
@ActiveProfiles("local")
@Transactional
class AuthServiceLocalProfileTest {

    @Autowired
    private AuthService authService;

    @Autowired
    private UserRepository userRepository;

    @Test
    void shouldPersistUserToH2FileDatabase() {
        LoginResponse response = authService.registerByUsername("localuser", "Password123", "Password123", null, null);

        assertThat(response.getUser()).isNotNull();
        assertThat(response.getUser().getUserId()).isNotNull();

        User persisted = userRepository.findById(response.getUser().getUserId()).orElseThrow();
        assertThat(persisted.getUsername()).isEqualTo("localuser");
    }
}
