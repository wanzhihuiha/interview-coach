package com.interviewcoach.admin.audit.interfaces.rest;

import com.interviewcoach.admin.audit.application.dto.AuditLogListResponse;
import com.interviewcoach.admin.audit.application.service.AuditLogAdminService;
import com.interviewcoach.common.response.ApiResponse;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 审计日志管理后台接口：管理员查看 Agent 工具调用审计记录。
 */
@RestController
@RequestMapping("/api/v1/admin/audit-logs")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AuditLogAdminController {

    private final AuditLogAdminService auditLogAdminService;

    /**
     * 分页查询审计日志。
     *
     * @param caller    调用者 Agent 类型
     * @param status    审计状态：ALLOWED、DENIED、FAILED
     * @param startTime 开始时间（ISO 日期时间格式）
     * @param endTime   结束时间（ISO 日期时间格式）
     * @param page      页码
     * @param size      每页大小
     */
    @GetMapping
    public ApiResponse<AuditLogListResponse> list(
            @RequestParam(value = "caller", required = false) String caller,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "startTime", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startTime,
            @RequestParam(value = "endTime", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endTime,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "20") int size) {
        return ApiResponse.success(auditLogAdminService.listAuditLogs(
                caller, status, startTime, endTime, page, size));
    }
}
