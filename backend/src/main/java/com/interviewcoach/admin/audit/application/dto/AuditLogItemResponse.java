package com.interviewcoach.admin.audit.application.dto;

import java.time.LocalDateTime;
import lombok.Data;

/**
 * 审计日志单项响应。
 */
@Data
public class AuditLogItemResponse {

    private Long id;
    private String caller;
    private String operation;
    private String methodKey;
    private String status;
    private Long durationMs;
    private String argsSummary;
    private String resultSummary;
    private String errorMessage;
    private LocalDateTime createdAt;
}
