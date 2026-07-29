package com.interviewcoach.common.security.agent;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * Agent 工具调用审计日志实体，对应数据库 agent_audit_log 表。
 */
@Entity
@Table(name = "agent_audit_log")
@Getter
@Setter
public class AgentAuditLogRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 调用者 Agent 类型。
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "caller", length = 50, nullable = false)
    private AgentType caller;

    /**
     * 审计操作名称。
     */
    @Column(name = "operation", length = 200)
    private String operation;

    /**
     * 目标方法标识，格式为：类简单名.方法名。
     */
    @Column(name = "method_key", length = 200, nullable = false)
    private String methodKey;

    /**
     * 调用结果状态：ALLOWED（允许）、DENIED（拒绝）、FAILED（异常）。
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    private AuditStatus status;

    /**
     * 方法执行耗时（毫秒）。
     */
    @Column(name = "duration_ms", nullable = false)
    private Long durationMs;

    /**
     * 参数摘要。
     */
    @Column(name = "args_summary", length = 2000)
    private String argsSummary;

    /**
     * 返回值摘要。
     */
    @Column(name = "result_summary", length = 500)
    private String resultSummary;

    /**
     * 错误信息摘要。
     */
    @Column(name = "error_message", length = 2000)
    private String errorMessage;

    /**
     * 记录创建时间。
     */
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }

    /**
     * 审计状态枚举。
     */
    public enum AuditStatus {
        ALLOWED("允许"),
        DENIED("拒绝"),
        FAILED("失败");

        private final String displayName;

        AuditStatus(String displayName) {
            this.displayName = displayName;
        }

        public String getDisplayName() {
            return displayName;
        }
    }
}
