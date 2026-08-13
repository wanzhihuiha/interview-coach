package com.interviewcoach.user.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 用户单次协议同意事实的持久化实体，对应数据库 {@code sys_user_consent} 表。
 *
 * <p>同意服务从已认证用户、提交请求和当前 HTTP 请求组装本实体并追加保存；同意状态查询和 AI 处理前置校验
 * 再按用户与类型判断是否存在历史记录。IP 和 User-Agent 属于可能识别请求环境的审计数据，不应无关传播。</p>
 */
@Entity
@Table(name = "sys_user_consent")
@Getter
@Setter
public class UserConsentRecord {

    /**
     * 数据库生成的同意记录主键，用于标识一次独立追加事实。
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 作出本次同意的用户主键，来源于认证安全上下文。
     */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /**
     * 本次同意的协议类型，以稳定英文枚举名持久化。
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "consent_type", length = 50, nullable = false)
    private ConsentType consentType;

    /**
     * 用户确认的协议版本文本；客户端未提供有效文本时由服务写入当前默认值 {@code 1.0}。
     */
    @Column(name = "consent_version", length = 20, nullable = false)
    private String consentVersion;

    /**
     * 记录首次持久化时的应用本地时间，由 {@link #onCreate()} 设置且之后不更新。
     */
    @Column(name = "consent_time", nullable = false, updatable = false)
    private LocalDateTime consentTime;

    /**
     * 提交请求携带或容器提供的 IP 审计文本；没有 HTTP 请求时可为 {@code null}。
     */
    @Column(name = "ip_address", length = 50)
    private String ipAddress;

    /**
     * 提交请求的 User-Agent 审计文本；请求缺少该头或没有 HTTP 请求时可为 {@code null}。
     */
    @Column(name = "user_agent", length = 500)
    private String userAgent;

    /**
     * JPA 首次持久化回调，将同意时间设置为当前应用本地时间。
     */
    @PrePersist
    protected void onCreate() {
        this.consentTime = LocalDateTime.now();
    }
}
