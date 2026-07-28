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
 * 用户同意记录实体，对应数据库 sys_user_consent 表。
 */
@Entity
@Table(name = "sys_user_consent")
@Getter
@Setter
public class UserConsentRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "consent_type", length = 50, nullable = false)
    private ConsentType consentType;

    @Column(name = "consent_version", length = 20, nullable = false)
    private String consentVersion;

    @Column(name = "consent_time", nullable = false, updatable = false)
    private LocalDateTime consentTime;

    @Column(name = "ip_address", length = 50)
    private String ipAddress;

    @Column(name = "user_agent", length = 500)
    private String userAgent;

    @PrePersist
    protected void onCreate() {
        this.consentTime = LocalDateTime.now();
    }
}
