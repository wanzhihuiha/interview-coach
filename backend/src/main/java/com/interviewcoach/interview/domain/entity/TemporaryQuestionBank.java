package com.interviewcoach.interview.domain.entity;

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
 * 临时题库：每次 LLM 生成的题目经去重后存入，等待管理员审核。
 *
 * <p>仅评估 Agent 可写入，评估 Agent 可读取。审核通过后可提升为永久题库。</p>
 */
@Entity
@Table(name = "temporary_question_bank")
@Getter
@Setter
public class TemporaryQuestionBank {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "job_category", nullable = false, length = 30)
    private String jobCategory;

    @Column(name = "phase", nullable = false, length = 30)
    private String phase;

    @Column(name = "topic_id", length = 50)
    private String topicId;

    @Column(name = "topic_name", length = 100)
    private String topicName;

    @Column(name = "content", nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(name = "expected_answer", columnDefinition = "TEXT")
    private String expectedAnswer;

    @Column(name = "source_interview_id")
    private Long sourceInterviewId;

    /**
     * 审核状态：PENDING（待审核）、APPROVED（已通过）、REJECTED（已拒绝）。
     */
    @Column(name = "status", nullable = false, length = 20)
    private String status = "PENDING";

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
