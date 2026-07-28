package com.interviewcoach.position.domain.entity;

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
import lombok.Getter;
import lombok.Setter;

/**
 * 岗位实体，对应数据库 position 表。
 */
@Entity
@Table(name = "\"position\"")
@Getter
@Setter
public class Position {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "position_name", nullable = false, length = 255)
    private String positionName;

    @Column(name = "company_name", length = 255)
    private String companyName;

    @Column(name = "location", length = 100)
    private String location;

    @Column(name = "salary_range", length = 50)
    private String salaryRange;

    @Column(name = "job_category", nullable = false, length = 30)
    private String jobCategory;

    @Column(name = "level", length = 20)
    private String level;

    @Column(name = "jd_content", columnDefinition = "TEXT")
    private String jdContent;

    @Enumerated(EnumType.STRING)
    @Column(name = "parse_status", nullable = false, length = 30)
    private PositionParseStatus parseStatus = PositionParseStatus.PENDING;

    @Enumerated(EnumType.STRING)
    @Column(name = "audit_status", nullable = false, length = 30)
    private PositionAuditStatus auditStatus = PositionAuditStatus.PENDING;

    @Column(name = "audit_remark", length = 500)
    private String auditRemark;

    @Column(name = "auditor_id")
    private Long auditorId;

    @Column(name = "audited_at")
    private LocalDateTime auditedAt;

    @Column(name = "is_public", nullable = false)
    private Boolean isPublic = false;

    @Column(name = "lock_interview_id")
    private Long lockInterviewId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * 判断岗位是否已被锁定（进入面试流程）。
     */
    public boolean isLocked() {
        return lockInterviewId != null;
    }
}
