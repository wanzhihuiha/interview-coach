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
 * 验证 local profile 下认证服务与 H2 文件数据库的装配和注册持久化链路。
 *
 * <p>测试通过真实 {@link AuthService} 注册账号，再由仓储在同一测试事务中读回；事务结束后回滚，不覆盖生产数据库配置。</p>
 */
@SpringBootTest
@ActiveProfiles("local")
@Transactional
class AuthServiceLocalProfileTest {

    /** Spring 容器按 local profile 装配的被测认证服务。 */
    @Autowired
    private AuthService authService;

    /** 从同一 H2 数据源读回注册记录、观察服务写入结果的真实仓储。 */
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
