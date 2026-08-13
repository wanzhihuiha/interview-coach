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
 * <p>审计切面通过 Spring Bean 代理调用 {@link #save(AgentAuditLogRecord)} 后，方法使用默认异步执行器
 * 和独立事务写入数据库。任务进入该方法后的数据库异常只写本地日志，不回传主调用链；
 * 异步调度阶段异常不在本方法的捕获范围内，该机制也不承诺进程退出或调度失败时必达。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentAuditLogStorageService {

    /** 在异步新事务中写入 {@code agent_audit_log} 表的 JPA 仓储。 */
    private final AgentAuditLogRepository auditLogRepository;

    /**
     * 通过 Spring 代理异步保存审计记录，并使用 {@code REQUIRES_NEW} 创建独立数据库事务。
     *
     * <p>方法内部捕获仓储异常，因此保存失败不回滚或替换原 Agent 调用结果；同类直接调用或手工实例化
     * 不经过 Spring 代理时不会获得异步行为。</p>
     */
    @Async
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void save(AgentAuditLogRecord record) {
        try {
            // 在当前异步线程的新事务中写入审计表，成功与否不改变原 Tool 调用结果。
            auditLogRepository.save(record);
        } catch (Exception e) {
            // 持久化异常在审计边界内终止，只记录方法标识和堆栈，不向主调用链回传。
            log.error("[AgentAuditLogStorage] 保存审计日志失败: {}", record.getMethodKey(), e);
        }
    }
}
