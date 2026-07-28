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
 * 主题评估实体。
 */
@Entity
@Table(name = "theme_evaluation")
@Getter
@Setter
public class ThemeEvaluation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "interview_id", nullable = false)
    private Long interviewId;

    @Column(name = "topic_id", nullable = false, length = 100)
    private String topicId;

    @Column(name = "topic_name", length = 100)
    private String topicName;

    @Column(name = "score")
    private Integer score;

    @Column(name = "depth_reached")
    private Integer depthReached;

    @Column(name = "strength_list", columnDefinition = "TEXT")
    private String strengthListJson;

    @Column(name = "weakness_list", columnDefinition = "TEXT")
    private String weaknessListJson;

    @Column(name = "key_events", columnDefinition = "TEXT")
    private String keyEventsJson;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }
}
