package com.interviewcoach.resume.domain.entity;

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
 * 当前解析代次下待用户核对的事实画像草稿；可由模型生成或用户编辑，确认后才转为正式画像。
 */
@Entity
@Table(name = "resume_profile_draft")
@Getter
@Setter
public class ResumeProfileDraft {

    /** 草稿记录的数据库主键。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 草稿所属简历 ID；数据库约束每份简历最多一条当前草稿。 */
    @Column(name = "resume_id", nullable = false, unique = true)
    private Long resumeId;

    /** 草稿所属用户 ID，用于查询和确认时限制资源归属。 */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 模型生成或用户编辑后的事实画像 JSON，尚未成为正式面试输入。 */
    @Column(name = "profile_data", nullable = false, columnDefinition = "TEXT")
    private String profileData;

    /** 根据草稿中的工作年限文本或经历时长确定性推导的经验等级稳定编码。 */
    @Column(name = "experience_level", length = 20)
    private String experienceLevel;

    /** 草稿绑定的事实解析代次，用于确认和保存编辑时防止旧页面覆盖新结果。 */
    @Column(name = "parse_generation", nullable = false)
    private Long parseGeneration;

    /** 草稿 JSON 的当前结构版本；版本 2 的升级依据和兼容记录缺失。 */
    @Column(name = "schema_version", nullable = false)
    private Integer schemaVersion = 2;

    /** 当前草稿记录首次创建时间。 */
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** 当前草稿最近一次由 JPA 实体更新的时间。 */
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /** 首次持久化时使用同一业务时间初始化创建和更新时间。 */
    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** JPA 更新草稿前刷新更新时间。 */
    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
