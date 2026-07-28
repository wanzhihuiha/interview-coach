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
import org.springframework.util.StringUtils;

/**
 * 用户实体，对应数据库 sys_user 表。
 */
@Entity
@Table(name = "sys_user")
@Getter
@Setter
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "username", length = 50, nullable = false, unique = true)
    private String username;

    @Column(name = "password", length = 255, nullable = false)
    private String password;

    @Column(name = "phone", length = 20, unique = true)
    private String phone;

    @Column(name = "email", length = 100, unique = true)
    private String email;

    @Column(name = "openid", length = 100, unique = true)
    private String openid;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    private UserStatus status = UserStatus.ACTIVE;

    /**
     * 用户角色，多个角色以逗号分隔（如 USER,ADMIN）。
     */
    @Column(name = "roles", length = 100, nullable = false)
    private String roles = UserRole.USER.name();

    @Column(name = "create_time", nullable = false, updatable = false)
    private LocalDateTime createTime;

    @Column(name = "update_time", nullable = false)
    private LocalDateTime updateTime;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        this.createTime = now;
        this.updateTime = now;
    }

    @PreUpdate
    protected void onUpdate() {
        this.updateTime = LocalDateTime.now();
    }

    /**
     * 判断用户是否可用。
     */
    public boolean isActive() {
        return status == UserStatus.ACTIVE;
    }

    /**
     * 获取角色列表。
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
     * 判断是否拥有指定角色。
     */
    public boolean hasRole(UserRole role) {
        return getRoleList().contains(role.name());
    }
}
