package com.interviewcoach.resume.domain.entity;

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
 * 基于已确认事实画像生成的 AI 辅助判断，不作为用户事实或评分结论；单行同时保存最近成功结果和最新任务状态。
 */
@Entity
@Table(name = "resume_profile_analysis")
@Getter
@Setter
public class ResumeProfileAnalysis {

    /** 辅助分析记录的数据库主键。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 分析所属简历 ID；数据库约束每份简历最多一条辅助分析记录。 */
    @Column(name = "resume_id", nullable = false, unique = true)
    private Long resumeId;

    /** 分析所属用户 ID，用于资源归属校验和额度结算。 */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 最近成功结果依据的正式画像哈希；从未成功生成结果时为空。 */
    @Column(name = "source_profile_hash", length = 64)
    private String sourceProfileHash;

    /** 最新任务绑定的正式画像哈希，用于阻止过期或错配结果写回。 */
    @Column(name = "task_profile_hash", length = 64)
    private String taskProfileHash;

    /** 最新辅助分析任务代次；每次接受新任务时递增。 */
    @Column(name = "task_generation", nullable = false)
    private Long taskGeneration = 0L;

    /** 最新任务的生成模式；尚未登记任务时可为空。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "task_mode", length = 30)
    private ResumeProfileAnalysisMode taskMode;

    /** 是否已经消耗过首次免费分析的模型调用资格；一旦为 true 不会因失败恢复为 false。 */
    @Column(name = "initial_model_call_started", nullable = false)
    private boolean initialModelCallStarted;

    /** 最近成功结果当前是否允许进入新面试；新任务登记或失败时为 false，成功写回时为 true。 */
    @Column(name = "usable_for_interview", nullable = false)
    private boolean usableForInterview;

    /** 手动任务通过额度准入时的日期；免费 INITIAL 或无需恢复结算时为空。 */
    @Column(name = "task_quota_date")
    private LocalDate taskQuotaDate;

    /** 手动任务的 Redis 额度 token，仅用于幂等结算和启动恢复。 */
    @Column(name = "task_quota_token", length = 64)
    private String taskQuotaToken;

    /** 最新任务状态，不等价于最近成功结果是否存在。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private ResumeProfileAnalysisStatus status = ResumeProfileAnalysisStatus.PENDING;

    /** 最近一次成功的辅助分析 JSON；后续任务运行或失败时仍可保留用于页面展示。 */
    @Column(name = "analysis_data", columnDefinition = "TEXT")
    private String analysisData;

    /** 最近成功结果的 JSON 结构版本；版本 1 的升级依据和兼容记录缺失。 */
    @Column(name = "schema_version", nullable = false)
    private Integer schemaVersion = 1;

    /** 分析行保存的 Prompt 版本；新建行预填当前版本，成功写回时刷新，任务执行中可能仍是旧成功结果版本。 */
    @Column(name = "prompt_version", nullable = false, length = 50)
    private String promptVersion;

    /** 预留的最近成功结果模型名称；当前成功写回路径未赋值，因此现有流程中可为空。 */
    @Column(name = "model_name", length = 100)
    private String modelName;

    /** 最新任务失败的稳定错误码；当前无错误时为空。 */
    @Column(name = "error_code", length = 50)
    private String errorCode;

    /** 最新任务失败的安全说明；不得保存敏感反馈、画像 JSON 或模型原文。 */
    @Column(name = "error_message", length = 500)
    private String errorMessage;

    /** 最近成功结果的生成时间；从未成功时为空。 */
    @Column(name = "generated_at")
    private LocalDateTime generatedAt;

    /** 辅助分析单行记录首次创建时间。 */
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** 辅助分析记录最近一次由 JPA 实体更新的时间。 */
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /** 首次持久化时使用同一业务时间初始化创建和更新时间。 */
    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** JPA 更新实体前刷新更新时间；仓储批量恢复更新不会触发该回调。 */
    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
