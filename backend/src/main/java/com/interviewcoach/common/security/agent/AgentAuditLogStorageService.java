package com.interviewcoach.common.security.agent;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Agent 审计日志持久化服务。
 *
 * <p>通过异步方式将审计记录写入数据库，避免影响主调用链路性能。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentAuditLogStorageService {

    private final AgentAuditLogRepository auditLogRepository;

    /**
     * 异步保存审计日志记录，使用独立事务避免影响主业务事务。
     */
    @Async
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void save(AgentAuditLogRecord record) {
        try {
            auditLogRepository.save(record);
        } catch (Exception e) {
            // 审计日志保存失败不应影响主业务流程，仅记录本地日志
            log.error("[AgentAuditLogStorage] 保存审计日志失败: {}", record.getMethodKey(), e);
        }
    }
}
