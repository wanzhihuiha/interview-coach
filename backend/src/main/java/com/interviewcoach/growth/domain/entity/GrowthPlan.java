package com.interviewcoach.growth.domain.entity;

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
 * 面试成长方案的持久化实体。
 *
 * <p>成长应用服务按认证用户和面试查找或创建本实体，生成时写入报告摘要、岗位快照、
 * Markdown 正文和完整响应 JSON；后续成长方案接口优先从结构化 JSON 恢复响应，解析失败时
 * 仍可使用 Markdown 正文降级展示。每个面试由数据库唯一约束最多关联一条记录。</p>
 */
@Entity
@Table(name = "growth_plan")
@Getter
@Setter
public class GrowthPlan {

    /** 成长方案数据库主键，由数据库自增生成并回填到 API 响应。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 方案所属的认证用户 ID，成长方案主查询使用它限制跨用户读取。 */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 来源面试 ID；数据库唯一约束保证一个面试最多保存一份成长方案。 */
    @Column(name = "interview_id", nullable = false, unique = true)
    private Long interviewId;

    /** 面试创建时保存的岗位名称快照，供成长方案页面标识目标岗位；可能为空。 */
    @Column(name = "position_title", length = 200)
    private String positionTitle;

    /** 生成时从面试报告复制的综合分数；报告没有值或生成未完成时可能为空。 */
    @Column(name = "overall_score")
    private Integer overallScore;

    /** 生成时从面试报告复制的等级文本；生成未完成时可能为空。 */
    @Column(name = "grade", length = 50)
    private String grade;

    /** 教练组件渲染的完整 Markdown 正文，结构化 JSON 不可用时作为降级响应内容。 */
    @Column(name = "content", columnDefinition = "TEXT")
    private String content;

    /** 完整 {@code GrowthPlanResponse} 的 JSON 快照，用于缓存命中时恢复结构化列表。 */
    @Column(name = "structured_data", columnDefinition = "TEXT")
    private String structuredData;

    /** 当前生成状态；新对象默认生成中，应用服务在成功或失败分支尝试更新。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private GrowthPlanStatus status = GrowthPlanStatus.GENERATING;

    /** 本次成功组装完成的本地时间；生成中或失败记录通常为空。 */
    @Column(name = "generated_at")
    private LocalDateTime generatedAt;

    /** 首次持久化前由 JPA 回调写入的本地创建时间。 */
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    /** 首次持久化及每次 JPA 实体更新前写入的本地修改时间。 */
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /** 首次入库前用同一时刻初始化创建时间和更新时间。 */
    @PrePersist
    public void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** JPA 更新实体前刷新更新时间，不改变创建时间。 */
    @PreUpdate
    public void preUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
