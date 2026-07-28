package com.interviewcoach.interview.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 面试消息实体。
 */
@Entity
@Table(name = "interview_message")
@Getter
@Setter
public class InterviewMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "interview_id", nullable = false)
    private Long interviewId;

    @Column(name = "phase", nullable = false, length = 30)
    private String phase;

    @Column(name = "role", nullable = false, length = 30)
    private String role;

    @Column(name = "content", nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(name = "topic_id", length = 100)
    private String topicId;

    @Column(name = "topic_name", length = 100)
    private String topicName;

    @Column(name = "depth")
    private Integer depth;

    @Column(name = "seq_no")
    private Integer seqNo;

    @Column(name = "token_count")
    private Integer tokenCount;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }
}
