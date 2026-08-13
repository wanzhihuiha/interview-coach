package com.interviewcoach.position.domain.entity;

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
 * 岗位当前解析任务的持久化实体，对应 {@code position_analysis_task} 表。
 * 每个岗位由唯一约束最多保留一行：提交事务写入 WAITING，调度与 Worker 推进状态，候选画像在确认前只保存在本记录中。
 */
@Entity
@Table(name = "position_analysis_task")
@Getter
@Setter
public class PositionAnalysisTask {

    /** 当前任务数据库主键，也是 Redis 投影、轮询和候选确认使用的任务代次。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 任务所属岗位 ID；数据库唯一约束保证一个岗位最多存在一条当前任务。 */
    @Column(name = "position_id", nullable = false, unique = true)
    private Long positionId;

    /** 发起本次解析的可信服务端用户 ID；公共岗位记录管理员，个人岗位记录所有者。 */
    @Column(name = "request_user_id", nullable = false)
    private Long requestUserId;

    /** 服务端生成的公共或个人队列参与者标识，用于 Redis 公平轮转并在数据库领取时复核。 */
    @Column(name = "queue_owner", nullable = false, length = 32)
    private String queueOwner;

    /** MySQL 中当前任务的最终状态事实，Redis 只保存可重建的调度投影。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private PositionAnalysisTaskStatus status = PositionAnalysisTaskStatus.WAITING;

    /** SUCCEEDED 任务等待用户确认的候选画像 JSON；其他状态以及确认删除任务后不存在。 */
    @Column(name = "candidate_profile_data", columnDefinition = "TEXT")
    private String candidateProfileData;

    /** FAILED 状态的稳定失败分类；非失败状态为空，不保存模型响应正文。 */
    @Column(name = "error_code", length = 50)
    private String errorCode;

    /** FAILED 状态可安全持久化和展示的脱敏说明；非失败状态为空。 */
    @Column(name = "error_message", length = 500)
    private String errorMessage;

    /** 任务从 WAITING 原子领取为 RUNNING 的时间；尚未领取时为空。 */
    @Column(name = "started_at")
    private LocalDateTime startedAt;

    /** 任务写入 SUCCEEDED 或 FAILED 的时间；等待或运行中为空。 */
    @Column(name = "finished_at")
    private LocalDateTime finishedAt;

    /** 当前任务首次持久化时间，由 {@link #onCreate()} 生成。 */
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** 当前任务最近更新时间；仓储批量更新语句会显式写入该列。 */
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /** 首次持久化前用同一个应用时间初始化创建和更新时间。 */
    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** 普通 JPA 实体更新前刷新更新时间；批量 JPQL 路径不触发本回调。 */
    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
