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
 * 管理员审核通过后供面试官复用的永久题目实体。
 *
 * <p>临时题目可由管理服务晋升为本实体；面试官按岗位、环节、主题和 {@link #usageCount}
 * 升序读取候选题。当前出题链路不会递增使用次数，因此该排序字段通常保持既有值。</p>
 */
@Entity
@Table(name = "permanent_question_bank")
@Getter
@Setter
public class PermanentQuestionBank {

    /** 数据库自增永久题目 ID。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 题目适用的岗位类别编码，用于题库分层筛选。 */
    @Column(name = "job_category", nullable = false, length = 30)
    private String jobCategory;

    /** 题目适用的面试环节英文编码。 */
    @Column(name = "phase", nullable = false, length = 30)
    private String phase;

    /** 题目适用的专业主题标识；通用环节或未分主题题目可为空。 */
    @Column(name = "topic_id", length = 50)
    private String topicId;

    /** 题目适用的主题展示名称，可为空。 */
    @Column(name = "topic_name", length = 100)
    private String topicName;

    /** 经审核的题目正文，题库精确去重使用该文本。 */
    @Column(name = "content", nullable = false, columnDefinition = "TEXT")
    private String content;

    /** 管理员可维护的参考答案；当前面试官采样只返回问题正文。 */
    @Column(name = "expected_answer", columnDefinition = "TEXT")
    private String expectedAnswer;

    /** 晋升来源的临时题目 ID；非晋升创建时可为空。 */
    @Column(name = "source_temporary_id")
    private Long sourceTemporaryId;

    /** 题目累计使用次数，默认 0；当前面试出题路径没有累加写入。 */
    @Column(name = "usage_count")
    private Integer usageCount = 0;

    /**
     * 当前约定的题目难度值，代码默认 3，管理端按 1～5 检查；该范围和默认值的精确依据缺失。
     */
    @Column(name = "difficulty_level")
    private Integer difficultyLevel = 3;

    /** 首次持久化时写入且不可更新的本地创建时间。 */
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** 首次持久化及每次 JPA 更新前刷新的本地更新时间。 */
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /** 首次持久化前用同一本地时间设置创建和更新时间。 */
    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** JPA 更新前刷新本地更新时间。 */
    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
