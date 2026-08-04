package com.interviewcoach.interview.domain.entity;

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
 * 面试会话实体。
 */
@Entity
@Table(name = "interview")
@Getter
@Setter
public class Interview {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "resume_id", nullable = false)
    private Long resumeId;

    @Column(name = "position_id", nullable = false)
    private Long positionId;

    @Column(name = "position_name_snapshot", nullable = false, length = 255)
    private String positionNameSnapshot;

    @Column(name = "company_name_snapshot", length = 255)
    private String companyNameSnapshot;

    @Column(name = "job_category_snapshot", nullable = false, length = 30)
    private String jobCategorySnapshot;

    @Column(name = "user_profile", columnDefinition = "TEXT")
    private String userProfileSnapshot;

    @Column(name = "user_profile_analysis", columnDefinition = "TEXT")
    private String userProfileAnalysisSnapshot;

    @Column(name = "position_profile", columnDefinition = "TEXT")
    private String positionProfileSnapshot;

    @Column(name = "selected_phases", nullable = false, length = 500)
    private String selectedPhases;

    @Enumerated(EnumType.STRING)
    @Column(name = "current_phase", nullable = false, length = 30)
    private InterviewPhase currentPhase;

    @Column(name = "current_topic_id", length = 100)
    private String currentTopicId;

    @Column(name = "current_topic_name", length = 100)
    private String currentTopicName;

    @Column(name = "current_depth")
    private Integer currentDepth = 1;

    @Column(name = "current_topic_follow_up_count")
    private Integer currentTopicFollowUpCount = 0;

    @Column(name = "consecutive_failures")
    private Integer consecutiveFailures = 0;

    @Column(name = "consecutive_excellence")
    private Integer consecutiveExcellence = 0;

    @Column(name = "last_evaluation_seq")
    private Integer lastEvaluationSeq = 0;

    @Column(name = "pending_question", columnDefinition = "TEXT")
    private String pendingQuestion;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private InterviewStatus status = InterviewStatus.IN_PROGRESS;

    @Column(name = "total_question_count")
    private Integer totalQuestionCount = 0;

    @Column(name = "current_phase_question_count")
    private Integer currentPhaseQuestionCount = 0;

    @Column(name = "self_intro_question_count")
    private Integer selfIntroQuestionCount = 0;

    @Column(name = "current_project_index")
    private Integer currentProjectIndex = 0;

    @Column(name = "current_behavioral_index")
    private Integer currentBehavioralIndex = 0;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "ended_at")
    private LocalDateTime endedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
        if (this.startedAt == null) {
            this.startedAt = now;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
