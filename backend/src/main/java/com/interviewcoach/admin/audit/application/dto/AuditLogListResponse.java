package com.interviewcoach.admin.audit.application.dto;

import java.util.List;
import lombok.Data;

/**
 * 审计日志列表响应。
 */
@Data
public class AuditLogListResponse {

    private List<AuditLogItemResponse> content;
    private Long totalElements;
    private Integer totalPages;
    private Integer currentPage;
}
