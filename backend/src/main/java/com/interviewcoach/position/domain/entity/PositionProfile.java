package com.interviewcoach.position.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
 * 已确认岗位正式画像的持久化实体，对应 {@code position_profile} 表。
 * 个人所有者或公共岗位管理员确认候选后写入，列表和详情据此判断画像可用，面试创建与出题流程读取其 JSON；未确认候选仍只保存在当前任务中。
 */
@Entity
@Table(name = "position_profile")
@Getter
@Setter
public class PositionProfile {

    /** 正式画像数据库主键，由数据库自增生成并通过画像接口返回。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 正式画像所属岗位 ID；唯一约束保证每个岗位最多一份当前正式画像。 */
    @Column(name = "position_id", nullable = false, unique = true)
    private Long positionId;

    /** 个人岗位画像所属用户 ID；公共岗位正式画像为空，不以该字段替代岗位资源归属校验。 */
    @Column(name = "user_id")
    private Long userId;

    /** 用户最终确认的 {@code PositionProfileData} JSON，是详情展示和面试流程使用的正式版本。 */
    @Column(name = "profile_data", nullable = false, columnDefinition = "TEXT")
    private String profileData;

    /** 正式画像首次创建时间，由 {@link #onCreate()} 生成。 */
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** 正式画像最近一次被新确认内容覆盖的时间。 */
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /** 首次持久化前用同一个应用时间初始化创建和更新时间。 */
    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** 普通 JPA 实体更新前刷新正式画像更新时间。 */
    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
