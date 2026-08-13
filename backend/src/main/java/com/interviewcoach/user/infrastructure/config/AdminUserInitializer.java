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

    /**
     * 负责按配置用户名查询账号并保存新管理员账号的用户仓储。
     */
    private final UserRepository userRepository;

    /**
     * 负责为新管理员保存一对一昵称资料的用户资料仓储。
     */
    private final UserProfileRepository userProfileRepository;

    /**
     * 负责将配置中的管理员明文密码编码为 BCrypt 密文的安全组件。
     */
    private final PasswordEncoder passwordEncoder;

    /**
     * 外部属性 {@code app.admin.username} 提供的管理员登录名；运行前会校验非空并去除首尾空白。
     */
    private final String adminUsername;

    /**
     * 外部属性 {@code app.admin.password} 提供的管理员明文密码，属于敏感认证数据；
     * 仅用于格式校验和 BCrypt 编码，不应记录、回显或提交到仓库。
     */
    private final String adminPassword;

    /**
     * 外部属性 {@code app.admin.nickname} 提供的资料昵称；未配置属性时当前默认值为“管理员”。
     */
    private final String adminNickname;

    /**
     * 由 Spring 注入账号仓储、资料仓储、密码编码器和外部管理员配置。
     *
     * @param userRepository 管理员账号查询与保存仓储
     * @param userProfileRepository 管理员资料保存仓储
     * @param passwordEncoder 管理员密码 BCrypt 编码器
     * @param adminUsername 外部配置的管理员用户名
     * @param adminPassword 外部配置的管理员明文密码
     * @param adminNickname 外部配置的管理员昵称，属性缺失时为“管理员”
     */
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
        // 在任何账号查询和写入前校验必填配置及统一密码策略，失败会终止启动回调。
        validateConfiguration();
        String username = adminUsername.trim();

        // 按去除首尾空白后的用户名查询账号，避免重复创建或自动覆盖既有账号。
        Optional<User> existingUser = userRepository.findByUsername(username);
        if (existingUser.isPresent()) {
            // 同名账号必须已经具有 ADMIN 角色；普通账号不会被自动提升权限。
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
        // 只把 BCrypt 编码结果写入实体，配置中的明文密码不会写入数据库或日志。
        admin.setPassword(passwordEncoder.encode(adminPassword));
        admin.setRoles(UserRole.ADMIN.name());
        // 先保存管理员账号取得主键，再在同一事务内创建其一对一资料。
        userRepository.save(admin);

        UserProfile profile = new UserProfile();
        profile.setUserId(admin.getId());
        profile.setNickname(adminNickname);
        // 资料与账号共享当前事务；任一步骤失败时两项数据库写入一起回滚。
        userProfileRepository.save(profile);

        log.info("[AdminUserInitializer] 已创建管理员账号");
        log.debug("[AdminUserInitializer] 已创建管理员账号: username={}", username);
    }

    /**
     * 校验管理员初始化所需用户名、敏感密码和统一密码规则。
     *
     * <p>任一条件不满足都会抛出 {@link IllegalStateException}，使当前启动初始化回调失败，
     * 且不会查询或写入管理员数据。</p>
     */
    private void validateConfiguration() {
        if (!StringUtils.hasText(adminUsername)) {
            throw new IllegalStateException("启用管理员初始化时必须配置 app.admin.username");
        }
        if (!StringUtils.hasText(adminPassword)) {
            throw new IllegalStateException("启用管理员初始化时必须配置 app.admin.password");
        }
        // 复用注册和改密入口的统一密码策略，格式不合格时拒绝继续启动初始化。
        if (!UserPasswordPolicy.isValid(adminPassword)) {
            throw new IllegalStateException(
                    "app.admin.password 格式错误：密码必须为8-20位，至少包含一个字母和一个数字，"
                            + "且只能使用字母、数字及@$!%*?&");
        }
    }
}
