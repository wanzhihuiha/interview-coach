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
 * 岗位聚合的持久化实体，对应数据库 {@code position} 表。
 * 状态服务创建和更新记录，列表、详情、解析任务及面试创建流程读取它；当前解析事实由任务表承载，遗留解析和审核列仅为旧结构兼容。
 */
@Entity
@Table(name = "\"position\"")
@Getter
@Setter
public class Position {

    /** 岗位数据库主键，由数据库自增生成并被任务、正式画像和面试记录引用。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 个人岗位所属用户 ID；公共岗位没有个人所有者，因此为空。 */
    @Column(name = "user_id")
    private Long userId;

    /** 调用方登记并用于列表、详情和面试上下文的岗位名称。 */
    @Column(name = "position_name", nullable = false, length = 255)
    private String positionName;

    /** JD 所属公司名称；创建请求未提供时为空。 */
    @Column(name = "company_name", length = 255)
    private String companyName;

    /** JD 中的工作地点；创建请求未提供时为空。 */
    @Column(name = "location", length = 100)
    private String location;

    /** JD 中的薪资范围文本；未提供时为空，不在本实体中换算金额或币种。 */
    @Column(name = "salary_range", length = 50)
    private String salaryRange;

    /** 岗位大类编码或当前存储值，用于接口展示映射并传入岗位分析上下文。 */
    @Column(name = "job_category", nullable = false, length = 30)
    private String jobCategory;

    /** 岗位职级编码或文本；确认正式画像时可由画像中的非空职级覆盖。 */
    @Column(name = "level", length = 20)
    private String level;

    /** 经服务端规范化后的 JD 正文，是后台模型分析的输入，可能包含求职业务信息。 */
    @Column(name = "jd_content", columnDefinition = "TEXT")
    private String jdContent;

    /**
     * V1 表结构保留的解析状态列；新解析流程以 {@link PositionAnalysisTask} 的当前任务状态为准，不读取本列判断进度。
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "parse_status", nullable = false, length = 30)
    private PositionParseStatus parseStatus = PositionParseStatus.PENDING;

    /**
     * V1 表结构保留的审核状态列；当前个人/公共岗位发布和解析流程不得以本列作为授权或可见性依据。
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "audit_status", nullable = false, length = 30)
    private PositionAuditStatus auditStatus = PositionAuditStatus.PENDING;

    /** V1 审核流程遗留的备注文本；当前新岗位流程不写入或读取该字段。 */
    @Column(name = "audit_remark", length = 500)
    private String auditRemark;

    /** V1 审核流程遗留的审核人 ID；当前新岗位流程不依赖该字段。 */
    @Column(name = "auditor_id")
    private Long auditorId;

    /** V1 审核流程遗留的审核时间；当前新岗位流程不依赖该字段。 */
    @Column(name = "audited_at")
    private LocalDateTime auditedAt;

    /** {@code true} 表示管理员创建的公共岗位，{@code false} 表示归属于 {@link #userId} 的个人岗位。 */
    @Column(name = "is_public", nullable = false)
    private Boolean isPublic = false;

    /** 归档发生时间；为空表示活动岗位，非空岗位不再允许确认或重新解析。 */
    @Column(name = "archived_at")
    private LocalDateTime archivedAt;

    /**
     * 当前占用该岗位的进行中面试 ID；面试创建时设置，结束、失败补偿或启动清理时按同一面试 ID 释放。
     */
    @Column(name = "lock_interview_id")
    private Long lockInterviewId;

    /** 岗位首次持久化时间，由 {@link #onCreate()} 使用应用进程本地时间生成。 */
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** 岗位最近一次普通 JPA 实体更新时间；JPQL 批量更新不会自动触发实体回调。 */
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /** 首次持久化前用同一个业务时间初始化创建和更新时间。 */
    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** 普通 JPA 实体更新前刷新更新时间；批量 JPQL 更新需要调用方显式维护时间列。 */
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

    /**
     * 判断岗位是否已经归档。
     */
    public boolean isArchived() {
        return archivedAt != null;
    }
}
