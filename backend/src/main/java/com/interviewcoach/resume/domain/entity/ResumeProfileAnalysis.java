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
 * 基于已确认事实画像生成的 AI 判断，不作为用户事实或评分结论。
 */
@Entity
@Table(name = "resume_profile_analysis")
@Getter
@Setter
public class ResumeProfileAnalysis {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "resume_id", nullable = false, unique = true)
    private Long resumeId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "source_profile_hash", length = 64)
    private String sourceProfileHash;

    @Column(name = "task_profile_hash", length = 64)
    private String taskProfileHash;

    @Column(name = "task_generation", nullable = false)
    private Long taskGeneration = 0L;

    @Enumerated(EnumType.STRING)
    @Column(name = "task_mode", length = 30)
    private ResumeProfileAnalysisMode taskMode;

    @Column(name = "initial_model_call_started", nullable = false)
    private boolean initialModelCallStarted;

    @Column(name = "usable_for_interview", nullable = false)
    private boolean usableForInterview;

    @Column(name = "task_quota_date")
    private LocalDate taskQuotaDate;

    @Column(name = "task_quota_token", length = 64)
    private String taskQuotaToken;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private ResumeProfileAnalysisStatus status = ResumeProfileAnalysisStatus.PENDING;

    @Column(name = "analysis_data", columnDefinition = "TEXT")
    private String analysisData;

    @Column(name = "schema_version", nullable = false)
    private Integer schemaVersion = 1;

    @Column(name = "prompt_version", nullable = false, length = 50)
    private String promptVersion;

    @Column(name = "model_name", length = 100)
    private String modelName;

    @Column(name = "error_code", length = 50)
    private String errorCode;

    @Column(name = "error_message", length = 500)
    private String errorMessage;

    @Column(name = "generated_at")
    private LocalDateTime generatedAt;

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
}
