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
 * 单轮评估附带写入、等待管理员审核的临时题目实体。
 *
 * <p>Evaluator 把本轮实际问题经题库工具精确去重后保存；管理端可编辑、通过或拒绝，审核
 * 通过时复制为永久题目。状态当前以字符串保存，实体本身不强制枚举约束。</p>
 */
@Entity
@Table(name = "temporary_question_bank")
@Getter
@Setter
public class TemporaryQuestionBank {

    /** 数据库自增临时题目 ID。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 来源面试的岗位类别编码，用于候选题分层和管理筛选。 */
    @Column(name = "job_category", nullable = false, length = 30)
    private String jobCategory;

    /** 来源问题所属的面试环节英文编码。 */
    @Column(name = "phase", nullable = false, length = 30)
    private String phase;

    /** 来源问题所属的主题标识，可为空。 */
    @Column(name = "topic_id", length = 50)
    private String topicId;

    /** 来源问题所属的主题展示名称，可为空。 */
    @Column(name = "topic_name", length = 100)
    private String topicName;

    /** 待审核问题正文，保存前与两类题库执行精确文本去重。 */
    @Column(name = "content", nullable = false, columnDefinition = "TEXT")
    private String content;

    /** 可选参考答案；Evaluator 附带写入时当前设置为空。 */
    @Column(name = "expected_answer", columnDefinition = "TEXT")
    private String expectedAnswer;

    /** 产生该问题的来源面试 ID，由 Evaluator 通过 DTO 的 id 字段传给题库工具。 */
    @Column(name = "source_interview_id")
    private Long sourceInterviewId;

    /**
     * 当前字符串审核状态：PENDING 待审核、APPROVED 已通过、REJECTED 已拒绝；默认待审核。
     */
    @Column(name = "status", nullable = false, length = 20)
    private String status = "PENDING";

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
