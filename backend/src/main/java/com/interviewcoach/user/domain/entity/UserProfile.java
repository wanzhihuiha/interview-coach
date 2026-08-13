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
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 用户账号的一对一扩展资料实体，对应数据库 {@code sys_user_profile} 表。
 *
 * <p>认证服务为新账号创建空白资料，资料服务按认证用户主键读取或部分更新本实体，并将字段转换为用户资料响应。
 * 账号认证信息仍保存在 {@link User}，本实体不保存密码、角色或登录状态。</p>
 */
@Entity
@Table(name = "sys_user_profile")
@Getter
@Setter
public class UserProfile {

    /**
     * 数据库生成的资料记录主键。
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 本资料所属用户的主键，数据库中保持一名用户最多一条资料记录。
     */
    @Column(name = "user_id", nullable = false, unique = true)
    private Long userId;

    /**
     * 用户设置的展示昵称，未设置时可为 {@code null}。
     */
    @Column(name = "nickname", length = 50)
    private String nickname;

    /**
     * 用户设置的头像地址，未设置时可为 {@code null}。
     */
    @Column(name = "avatar", length = 500)
    private String avatar;

    /**
     * 用户资料中的性别稳定码，以英文枚举名持久化；新建实体默认 {@link Gender#UNKNOWN}。
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "gender", length = 10)
    private Gender gender = Gender.UNKNOWN;

    /**
     * 用户填写的生日日期，不包含时区和时刻，未设置时可为 {@code null}。
     */
    @Column(name = "birthday")
    private LocalDate birthday;

    /**
     * 用户填写的个人简介，未设置时可为 {@code null}。
     */
    @Column(name = "bio", length = 500)
    private String bio;

    /**
     * 资料首次持久化时间，由 {@link #onCreate()} 在插入前设置，之后不参与更新。
     */
    @Column(name = "create_time", nullable = false, updatable = false)
    private LocalDateTime createTime;

    /**
     * 资料最近一次持久化更新时间，由创建和更新回调使用应用本地时间设置。
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
}
