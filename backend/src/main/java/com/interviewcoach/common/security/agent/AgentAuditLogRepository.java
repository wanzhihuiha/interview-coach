package com.interviewcoach.common.security.agent;

import java.time.LocalDateTime;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Agent 审计日志数据访问接口。
 */
@Repository
public interface AgentAuditLogRepository extends JpaRepository<AgentAuditLogRecord, Long> {

    /**
     * 按调用者与状态分页查询审计日志。
     */
    Page<AgentAuditLogRecord> findByCallerAndStatus(AgentType caller,
                                                     AgentAuditLogRecord.AuditStatus status,
                                                     Pageable pageable);

    /**
     * 按调用者分页查询审计日志。
     */
    Page<AgentAuditLogRecord> findByCaller(AgentType caller, Pageable pageable);

    /**
     * 按状态分页查询审计日志。
     */
    Page<AgentAuditLogRecord> findByStatus(AgentAuditLogRecord.AuditStatus status, Pageable pageable);

    /**
     * 按创建时间范围分页查询审计日志。
     */
    Page<AgentAuditLogRecord> findByCreatedAtBetween(LocalDateTime startTime,
                                                      LocalDateTime endTime,
                                                      Pageable pageable);

    /**
     * 按创建时间范围统计审计日志数量。
     */
    long countByCreatedAtBetween(LocalDateTime startTime, LocalDateTime endTime);
}
