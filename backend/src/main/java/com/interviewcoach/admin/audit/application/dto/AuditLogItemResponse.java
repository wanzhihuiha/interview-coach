package com.interviewcoach.admin.audit.application.dto;

import java.time.LocalDateTime;
import lombok.Data;

/**
 * 管理端审计日志列表中的单条记录。
 *
 * <p>数据由审计日志实体映射后跨 HTTP 边界返回，供管理员识别 Agent 调用者、执行结果、
 * 耗时和审计摘要；本对象不包含被调用方法的完整参数或返回值。</p>
 */
@Data
public class AuditLogItemResponse {

    /**
     * 审计日志表中的记录主键。
     */
    private Long id;

    /**
     * 调用者 Agent 的稳定英文编码；实体未记录调用者时为 {@code null}。
     */
    private String caller;

    /**
     * 与 {@link #caller} 对应的中文名称；调用者为空时同样为 {@code null}。
     */
    private String callerLabel;

    /**
     * 审计注解声明的操作名称；注解未提供名称时使用目标方法标识。
     */
    private String operation;

    /**
     * 被审计方法标识，当前格式为“类简单名.方法名”。
     */
    private String methodKey;

    /**
     * 调用结果的稳定英文编码，当前可能为 ALLOWED、DENIED 或 FAILED。
     */
    private String status;

    /**
     * 与 {@link #status} 对应的中文名称；状态为空时为 {@code null}。
     */
    private String statusLabel;

    /**
     * 审计切面从开始调用到返回或抛出异常所记录的耗时，单位为毫秒。
     */
    private Long durationMs;

    /**
     * 参数摘要；启用参数审计时仅记录参数类型列表，未启用时为 {@code null}。
     */
    private String argsSummary;

    /**
     * 返回值摘要；启用结果审计且调用成功时记录返回值类型，其他情况可为 {@code null}。
     */
    private String resultSummary;

    /**
     * 调用被拒绝或执行失败时保存的错误消息摘要，成功调用通常为 {@code null}。
     */
    private String errorMessage;

    /**
     * 审计实体首次持久化时由应用进程写入的本地日期时间，不携带时区。
     */
    private LocalDateTime createdAt;
}
