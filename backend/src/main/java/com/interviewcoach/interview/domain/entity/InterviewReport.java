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
 * 一场面试首次生成后缓存的评估报告实体。
 *
 * <p>报告查询先按面试归属校验，再读取本实体；缓存未命中时应用服务计算分数、调用报告 Agent、
 * 做有限字段替换并保存。多个 TEXT 列保存 JSON，读取时会反序列化并重新构建 Markdown。</p>
 */
@Entity
@Table(name = "interview_report")
@Getter
@Setter
public class InterviewReport {

    /** 数据库自增报告 ID。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 报告对应的唯一面试 ID，同一场面试最多保存一条报告。 */
    @Column(name = "interview_id", nullable = false, unique = true)
    private Long interviewId;

    /** 报告所属用户 ID；当前读取前由面试归属查询守住权限边界。 */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 应用服务按候选人回答字符数公式计算的综合分。 */
    @Column(name = "overall_score")
    private Integer overallScore;

    /** 综合分按固定阈值映射出的中文等级。 */
    @Column(name = "grade", length = 20)
    private String grade;

    /** 以环节编码为键的完成状态和面试官问题数 JSON。 */
    @Column(name = "phases", columnDefinition = "TEXT")
    private String phases;

    /** 由综合分和固定偏移组装的五维分数 JSON。 */
    @Column(name = "dimensions", columnDefinition = "TEXT")
    private String dimensions;

    /** 报告 Agent 或本地降级生成的优势字符串列表 JSON。 */
    @Column(name = "strengths", columnDefinition = "TEXT")
    private String strengths;

    /** 报告 Agent 或本地降级生成的薄弱点字符串列表 JSON，成长方案会消费该列表。 */
    @Column(name = "weaknesses", columnDefinition = "TEXT")
    private String weaknesses;

    /** 报告关键事件字符串列表 JSON；当前生成流程固定保存空列表。 */
    @Column(name = "key_events", columnDefinition = "TEXT")
    private String keyEvents;

    /** 根据等级和薄弱点拼接，并经过有限字段替换的结论文本。 */
    @Column(name = "conclusion", columnDefinition = "TEXT")
    private String conclusion;

    /** 首次生成时保存的 Markdown；当前读取缓存时会根据结构化字段重新构建响应正文。 */
    @Column(name = "md_content", columnDefinition = "TEXT")
    private String mdContent;

    /** 首次持久化时写入且不可更新的本地创建时间。 */
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** 首次持久化及每次 JPA 更新前刷新的本地更新时间。 */
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /** 首次持久化前使用同一本地时间设置创建和更新时间。 */
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
