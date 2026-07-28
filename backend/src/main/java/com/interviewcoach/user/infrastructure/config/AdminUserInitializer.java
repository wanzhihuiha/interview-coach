package com.interviewcoach.user.infrastructure.config;

import com.interviewcoach.user.domain.entity.User;
import com.interviewcoach.user.domain.entity.UserProfile;
import com.interviewcoach.user.domain.entity.UserRole;
import com.interviewcoach.user.domain.repository.UserProfileRepository;
import com.interviewcoach.user.domain.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 默认管理员账号初始化器。
 *
 * <p>应用启动时检查 admin 用户是否存在，不存在则创建，便于本地开发和测试。
 * 生产环境可通过设置 {@code app.admin.init-enabled=false} 关闭。</p>
 */
@Slf4j
@Component
@Order(100)
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.admin", name = "init-enabled", havingValue = "true", matchIfMissing = true)
public class AdminUserInitializer implements CommandLineRunner {

    private static final String ADMIN_USERNAME = "admin";
    private static final String ADMIN_PASSWORD = "Admin@1234";
    private static final String ADMIN_NICKNAME = "管理员";

    private final UserRepository userRepository;
    private final UserProfileRepository userProfileRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    @Transactional
    public void run(String... args) {
        if (userRepository.existsByUsername(ADMIN_USERNAME)) {
            log.info("[AdminUserInitializer] 管理员账号已存在，跳过初始化");
            return;
        }

        User admin = new User();
        admin.setUsername(ADMIN_USERNAME);
        admin.setPassword(passwordEncoder.encode(ADMIN_PASSWORD));
        admin.setRoles(UserRole.ADMIN.name());
        userRepository.save(admin);

        UserProfile profile = new UserProfile();
        profile.setUserId(admin.getId());
        profile.setNickname(ADMIN_NICKNAME);
        userProfileRepository.save(profile);

        log.info("[AdminUserInitializer] 已创建默认管理员账号: username={}", ADMIN_USERNAME);
    }
}
