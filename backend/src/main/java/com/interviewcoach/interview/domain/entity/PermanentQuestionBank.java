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
 * 永久题库：经管理员审核后的高质量题目，可供后续面试复用。
 *
 * <p>评估 Agent 和其他 Agent（如面试官 Agent）均可读取，但不可直接写入。</p>
 */
@Entity
@Table(name = "permanent_question_bank")
@Getter
@Setter
public class PermanentQuestionBank {

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

    @Column(name = "source_temporary_id")
    private Long sourceTemporaryId;

    @Column(name = "usage_count")
    private Integer usageCount = 0;

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
