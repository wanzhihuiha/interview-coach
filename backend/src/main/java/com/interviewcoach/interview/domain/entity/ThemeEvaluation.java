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
 * 早期设计保留的单主题评估持久化映射。
 *
 * <p>当前主应用没有创建、保存或读取本实体；现行单轮评分只形成内存中的
 * {@code EvaluationResult/EvaluationSignal}，报告分数也由回答字符数计算。因此不能把本表
 * 理解为当前评分来源，本实体和仓储仅保留既有数据库映射。</p>
 */
@Entity
@Table(name = "theme_evaluation")
@Getter
@Setter
public class ThemeEvaluation {

    /** 数据库自增主题评估 ID。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 历史主题评估所属面试 ID。 */
    @Column(name = "interview_id", nullable = false)
    private Long interviewId;

    /** 历史评估对应的主题标识。 */
    @Column(name = "topic_id", nullable = false, length = 100)
    private String topicId;

    /** 历史评估对应的主题展示名称，可为空。 */
    @Column(name = "topic_name", length = 100)
    private String topicName;

    /** 历史主题综合分字段；当前主流程不写入或读取。 */
    @Column(name = "score")
    private Integer score;

    /** 历史主题达到的深度字段；当前主流程不写入或读取。 */
    @Column(name = "depth_reached")
    private Integer depthReached;

    /** 历史优势列表 JSON；当前主流程不反序列化。 */
    @Column(name = "strength_list", columnDefinition = "TEXT")
    private String strengthListJson;

    /** 历史薄弱点列表 JSON；当前主流程不反序列化。 */
    @Column(name = "weakness_list", columnDefinition = "TEXT")
    private String weaknessListJson;

    /** 历史关键事件列表 JSON；当前主流程不反序列化。 */
    @Column(name = "key_events", columnDefinition = "TEXT")
    private String keyEventsJson;

    /** 首次持久化时写入且不可更新的本地创建时间。 */
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** 如果该遗留实体被保存，JPA 首次持久化前写入当前本地时间。 */
    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }
}
