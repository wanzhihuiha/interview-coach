package com.interviewcoach.common.security.agent;

import java.time.LocalDateTime;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Agent 审计日志的 Spring Data JPA 仓储。
 *
 * <p>异步存储服务使用继承的 {@code save} 写入记录；管理后台服务按调用者、状态或时间范围
 * 分页读取，仪表盘使用时间范围统计结果。分页大小、页码和排序由调用方的 {@link Pageable} 决定。</p>
 */
@Repository
public interface AgentAuditLogRepository extends JpaRepository<AgentAuditLogRecord, Long> {

    /**
     * 同时按调用 Agent 和审计状态筛选，并按调用方分页规则返回记录。
     */
    Page<AgentAuditLogRecord> findByCallerAndStatus(AgentType caller,
                                                     AgentAuditLogRecord.AuditStatus status,
                                                     Pageable pageable);

    /**
     * 仅按调用 Agent 筛选，并按调用方分页规则返回记录。
     */
    Page<AgentAuditLogRecord> findByCaller(AgentType caller, Pageable pageable);

    /**
     * 仅按审计状态筛选，并按调用方分页规则返回记录。
     */
    Page<AgentAuditLogRecord> findByStatus(AgentAuditLogRecord.AuditStatus status, Pageable pageable);

    /**
     * 按创建时间的闭区间筛选，并按调用方分页规则返回记录。
     */
    Page<AgentAuditLogRecord> findByCreatedAtBetween(LocalDateTime startTime,
                                                      LocalDateTime endTime,
                                                      Pageable pageable);

    /**
     * 统计创建时间闭区间内的审计记录数量，供管理仪表盘生成当前时间段指标。
     */
    long countByCreatedAtBetween(LocalDateTime startTime, LocalDateTime endTime);
}
