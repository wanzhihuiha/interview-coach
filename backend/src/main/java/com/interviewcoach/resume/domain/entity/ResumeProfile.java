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
 * 用户已确认的简历事实画像，对应数据库 resume_profile 表；面试创建读取该记录作为候选人事实输入。
 */
@Entity
@Table(name = "resume_profile")
@Getter
@Setter
public class ResumeProfile {

    /** 正式画像记录的数据库主键。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 画像所属简历 ID；数据库约束每份简历最多一条正式画像。 */
    @Column(name = "resume_id", nullable = false, unique = true)
    private Long resumeId;

    /** 正式画像所属用户 ID，用于仓储查询时限制资源归属。 */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 规范化并校验后的事实画像 JSON，不包含独立辅助分析结论。 */
    @Column(name = "profile_data", nullable = false, columnDefinition = "TEXT")
    private String profileData;

    /** 根据事实画像中的工作年限文本或经历时长确定性推导的经验等级稳定编码。 */
    @Column(name = "experience_level", length = 20)
    private String experienceLevel;

    /** 规范化事实画像的 SHA-256 哈希，用于判断辅助分析结果是否仍匹配。 */
    @Column(name = "profile_hash", length = 64)
    private String profileHash;

    /** 正式画像 JSON 的当前结构版本；版本 2 的升级依据和兼容记录缺失。 */
    @Column(name = "schema_version", nullable = false)
    private Integer schemaVersion = 2;

    /** 用户最近一次确认该正式事实画像的时间。 */
    @Column(name = "confirmed_at")
    private LocalDateTime confirmedAt;

    /** 正式画像记录首次创建时间。 */
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** 正式画像记录最近一次由 JPA 实体更新的时间。 */
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /** 首次持久化时使用同一业务时间初始化创建和更新时间。 */
    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** JPA 更新实体前刷新更新时间。 */
    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
