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
 * 一场面试中的单条面试官或候选人消息。
 *
 * <p>创建状态服务保存首题；轮次状态服务在同一事务中成对保存候选人回答和下一题。接口按
 * {@link #seqNo} 升序恢复完整问答，报告服务也读取同一消息集合进行评分和文本分析。</p>
 */
@Entity
@Table(name = "interview_message")
@Getter
@Setter
public class InterviewMessage {

    /** 数据库自增消息 ID。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 所属面试 ID；消息仓储的所有业务查询均以该值限定会话范围。 */
    @Column(name = "interview_id", nullable = false)
    private Long interviewId;

    /** 消息产生时的面试环节英文编码，候选人回答保留旧题环节，下一题使用推进后环节。 */
    @Column(name = "phase", nullable = false, length = 30)
    private String phase;

    /** 发送方编码；当前主流程写入 {@code interviewer} 或 {@code candidate}。 */
    @Column(name = "role", nullable = false, length = 30)
    private String role;

    /** 面试问题、结束语或候选人回答原文，可能包含个人经历等敏感信息。 */
    @Column(name = "content", nullable = false, columnDefinition = "TEXT")
    private String content;

    /** 消息产生时的主题标识；未建立主题的环节可为空。 */
    @Column(name = "topic_id", length = 100)
    private String topicId;

    /** 消息产生时的主题、项目或场景名称，可为空。 */
    @Column(name = "topic_name", length = 100)
    private String topicName;

    /** 消息产生时的服务端题目深度；非专业环节可为空。 */
    @Column(name = "depth")
    private Integer depth;

    /** 同一面试内连续消息序号；首题为 1，每轮回答和下一题依次占用两个序号。 */
    @Column(name = "seq_no")
    private Integer seqNo;

    /** 预留的消息 Token 计数字段；当前主应用没有写入者，通常保持为空。 */
    @Column(name = "token_count")
    private Integer tokenCount;

    /** 首次持久化时由回调写入且后续不可更新的本地创建时间。 */
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** JPA 首次持久化前写入当前本地时间。 */
    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }
}
