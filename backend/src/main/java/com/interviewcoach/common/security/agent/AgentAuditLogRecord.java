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
 * Agent 审计切面创建、异步存储服务写入的调用审计实体，对应 {@code agent_audit_log} 表。
 *
 * <p>管理后台按调用者、状态或时间读取该实体并转换为响应；参数和结果字段只保存类型摘要，
 * 不保存 Tool 参数或返回值正文。</p>
 */
@Entity
@Table(name = "agent_audit_log")
@Getter
@Setter
public class AgentAuditLogRecord {

    /** 数据库自增生成的审计记录主键，实体首次持久化前为 {@code null}。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 发起受审计方法调用的 Agent 身份；调用链未建立身份时可在实体内为 {@code null}，
     * 但当前数据库列声明为非空，存储失败会被异步服务记录并隔离。
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "caller", length = 50, nullable = false)
    private AgentType caller;

    /**
     * 注解指定的操作名；注解值为空时由切面写入目标方法标识。
     */
    @Column(name = "operation", length = 200)
    private String operation;

    /**
     * 权限和审计切面共同使用的目标方法标识，格式为 {@code 类简单名.方法名}。
     */
    @Column(name = "method_key", length = 200, nullable = false)
    private String methodKey;

    /**
     * 切面根据目标返回、权限拒绝或其他异常写入的稳定审计状态。
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    private AuditStatus status;

    /**
     * 从进入审计切面到返回或抛错的耗时，单位为毫秒。
     */
    @Column(name = "duration_ms", nullable = false)
    private Long durationMs;

    /**
     * 参数运行时类型摘要；未开启 {@code logArgs} 时为 {@code null}，不包含参数正文。
     */
    @Column(name = "args_summary", length = 2000)
    private String argsSummary;

    /**
     * 返回值运行时类型摘要；未开启 {@code logResult}、拒绝或失败时为 {@code null}。
     */
    @Column(name = "result_summary", length = 500)
    private String resultSummary;

    /**
     * 拒绝原因或失败异常消息的截断摘要；调用成功时为 {@code null}。
     */
    @Column(name = "error_message", length = 2000)
    private String errorMessage;

    /**
     * JPA 首次持久化前由 {@link #onCreate()} 写入的本地日期时间，后续更新不再改写。
     */
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /**
     * 在 JPA 首次插入前写入当前本地日期时间，覆盖调用方预先设置的该字段值。
     */
    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }

    /**
     * 持久化为英文枚举名、供管理端同时返回稳定编码和中文 Label 的审计状态。
     */
    public enum AuditStatus {
        /** 权限检查和目标方法均正常完成。 */
        ALLOWED("允许"),
        /** 权限切面拒绝调用，目标方法未执行。 */
        DENIED("拒绝"),
        /** 权限检查或目标方法抛出非拒绝异常。 */
        FAILED("失败");

        /** 管理端响应中与稳定英文状态编码配套的中文 Label。 */
        private final String displayName;

        AuditStatus(String displayName) {
            this.displayName = displayName;
        }

        public String getDisplayName() {
            return displayName;
        }
    }
}
