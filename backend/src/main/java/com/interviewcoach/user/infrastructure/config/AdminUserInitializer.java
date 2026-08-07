package com.interviewcoach.user.infrastructure.config;

import com.interviewcoach.user.domain.entity.User;
import com.interviewcoach.user.domain.entity.UserProfile;
import com.interviewcoach.user.domain.entity.UserRole;
import com.interviewcoach.user.domain.repository.UserProfileRepository;
import com.interviewcoach.user.domain.repository.UserRepository;
import com.interviewcoach.user.domain.service.UserPasswordPolicy;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 可选的首次管理员账号初始化器。
 *
 * <p>仅在显式启用 {@code app.admin.init-enabled} 时运行，账号和密码必须由外部配置提供，
 * 不使用内置凭据。若同名管理员已存在则幂等跳过且不修改密码；若同名非管理员账号已存在，
 * 则拒绝启动，避免自动提升已有账号权限。</p>
 */
@Slf4j
@Component
@Order(100)
@ConditionalOnProperty(prefix = "app.admin", name = "init-enabled", havingValue = "true")
public class AdminUserInitializer implements CommandLineRunner {

    private final UserRepository userRepository;
    private final UserProfileRepository userProfileRepository;
    private final PasswordEncoder passwordEncoder;
    private final String adminUsername;
    private final String adminPassword;
    private final String adminNickname;

    public AdminUserInitializer(
            UserRepository userRepository,
            UserProfileRepository userProfileRepository,
            PasswordEncoder passwordEncoder,
            @Value("${app.admin.username:}") String adminUsername,
            @Value("${app.admin.password:}") String adminPassword,
            @Value("${app.admin.nickname:管理员}") String adminNickname) {
        this.userRepository = userRepository;
        this.userProfileRepository = userProfileRepository;
        this.passwordEncoder = passwordEncoder;
        this.adminUsername = adminUsername;
        this.adminPassword = adminPassword;
        this.adminNickname = adminNickname;
    }

    /**
     * 在单个事务内创建管理员及其资料，任一步骤失败时整体回滚。
     *
     * @param args Spring Boot 启动参数，本初始化器不使用
     */
    @Override
    @Transactional
    public void run(String... args) {
        validateConfiguration();
        String username = adminUsername.trim();

        Optional<User> existingUser = userRepository.findByUsername(username);
        if (existingUser.isPresent()) {
            if (!existingUser.get().hasRole(UserRole.ADMIN)) {
                log.warn("[AdminUserInitializer] 初始化失败：配置的用户名已被非管理员账号占用");
                log.debug("[AdminUserInitializer] 冲突账号: username={}", username);
                throw new IllegalStateException("配置的管理员用户名已被非管理员账号占用");
            }

            log.info("[AdminUserInitializer] 管理员账号已存在，跳过初始化且不重置密码");
            log.debug("[AdminUserInitializer] 已存在的管理员账号: username={}", username);
            return;
        }

        User admin = new User();
        admin.setUsername(username);
        admin.setPassword(passwordEncoder.encode(adminPassword));
        admin.setRoles(UserRole.ADMIN.name());
        userRepository.save(admin);

        UserProfile profile = new UserProfile();
        profile.setUserId(admin.getId());
        profile.setNickname(adminNickname);
        userProfileRepository.save(profile);

        log.info("[AdminUserInitializer] 已创建管理员账号");
        log.debug("[AdminUserInitializer] 已创建管理员账号: username={}", username);
    }

    private void validateConfiguration() {
        if (!StringUtils.hasText(adminUsername)) {
            throw new IllegalStateException("启用管理员初始化时必须配置 app.admin.username");
        }
        if (!StringUtils.hasText(adminPassword)) {
            throw new IllegalStateException("启用管理员初始化时必须配置 app.admin.password");
        }
        if (!UserPasswordPolicy.isValid(adminPassword)) {
            throw new IllegalStateException(
                    "app.admin.password 格式错误：密码必须为8-20位，至少包含一个字母和一个数字，"
                            + "且只能使用字母、数字及@$!%*?&");
        }
    }
}
