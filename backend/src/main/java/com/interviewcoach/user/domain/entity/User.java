package com.interviewcoach.user.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.DynamicUpdate;
import org.springframework.util.StringUtils;

/**
 * 用户账号的持久化实体，对应数据库 {@code sys_user} 表。
 *
 * <p>认证、资料、管理端和岗位服务通过用户仓储读取或保存本实体；认证服务把其中的账号状态、
 * 英文角色码和脱敏联系方式转换为登录或资料响应。实体包含密码密文和联系方式等敏感数据，
 * 不应直接作为 HTTP 响应返回。</p>
 */
@Entity
@Table(name = "sys_user")
@DynamicUpdate
@Getter
@Setter
public class User {

    /**
     * 数据库生成的用户主键，也是安全上下文和各用户资源归属使用的账号标识。
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 登录和账号查询使用的唯一用户名，不存放展示昵称。
     */
    @Column(name = "username", length = 50, nullable = false, unique = true)
    private String username;

    /**
     * BCrypt 编码后的密码密文，属于敏感认证数据，不应记录、回显或解释为明文密码。
     */
    @Column(name = "password", length = 255, nullable = false)
    private String password;

    /**
     * 可选且唯一的绑定手机号，用于验证码登录和账号绑定；登录及个人资料响应按当前服务规则掩码。
     */
    @Column(name = "phone", length = 20, unique = true)
    private String phone;

    /**
     * 可选且唯一的账号邮箱；当前个人资料响应会尝试掩码后返回。
     */
    @Column(name = "email", length = 100, unique = true)
    private String email;

    /**
     * 可选且唯一的微信 OpenID 关联值；当前微信登录写入的是本地派生的模拟标识。
     */
    @Column(name = "openid", length = 100, unique = true)
    private String openid;

    /**
     * 账号状态，以英文枚举名持久化；新建实体默认 {@link UserStatus#ACTIVE}。
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    private UserStatus status = UserStatus.ACTIVE;

    /**
     * 账号的英文角色码文本，多个值以逗号分隔（如 {@code USER,ADMIN}）；新建实体默认 {@code USER}。
     */
    @Column(name = "roles", length = 100, nullable = false)
    private String roles = UserRole.USER.name();

    /**
     * 该用户最近一次成功提交个人岗位分析的本地时间；岗位服务用它检查再次提交间隔，未提交过时为 {@code null}。
     */
    @Column(name = "last_position_analysis_submitted_at")
    private LocalDateTime lastPositionAnalysisSubmittedAt;

    /**
     * 账号首次持久化时间，由 {@link #onCreate()} 在插入前设置，之后不参与更新。
     */
    @Column(name = "create_time", nullable = false, updatable = false)
    private LocalDateTime createTime;

    /**
     * 账号最近一次持久化更新时间，由创建和更新回调使用应用本地时间设置。
     */
    @Column(name = "update_time", nullable = false)
    private LocalDateTime updateTime;

    /**
     * JPA 首次持久化回调，使用同一个当前本地时间初始化创建时间和更新时间。
     */
    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        this.createTime = now;
        this.updateTime = now;
    }

    /**
     * JPA 更新前回调，将更新时间替换为当前应用本地时间，不修改创建时间。
     */
    @PreUpdate
    protected void onUpdate() {
        this.updateTime = LocalDateTime.now();
    }

    /**
     * 判断账号当前是否处于允许认证服务签发新令牌的正常状态。
     *
     * @return 状态严格等于 {@link UserStatus#ACTIVE} 时为 {@code true}
     */
    public boolean isActive() {
        return status == UserStatus.ACTIVE;
    }

    /**
     * 将逗号分隔角色字段转换为供 JWT 和 API 使用的英文角色码列表。
     *
     * <p>字段为空或纯空白时回退为单个 {@code USER}；其他情况会去除每段首尾空白并过滤空段，
     * 但保留未知值和重复值。</p>
     *
     * @return 当前角色码列表
     */
    public List<String> getRoleList() {
        if (!StringUtils.hasText(roles)) {
            return Collections.singletonList(UserRole.USER.name());
        }
        return Arrays.stream(roles.split(","))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .collect(Collectors.toList());
    }

    /**
     * 判断解析后的英文角色码列表是否包含指定枚举名称。
     *
     * @param role 待检查角色；当前实现要求非 {@code null}
     * @return 包含该角色名称时为 {@code true}
     */
    public boolean hasRole(UserRole role) {
        // 复用当前逗号拆分和空值回退规则，再按稳定英文枚举名判断角色。
        return getRoleList().contains(role.name());
    }
}
