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
 * 管理员查看 Agent 工具调用审计记录的 HTTP 入口。
 *
 * <p>类级权限表达式要求当前 JWT 安全上下文包含 ADMIN 角色；入口只负责接收筛选和分页参数，
 * 实际筛选优先级与实体映射由审计日志管理服务完成。</p>
 */
@RestController
@RequestMapping("/api/v1/admin/audit-logs")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AuditLogAdminController {

    /**
     * 执行审计日志条件查询并组装管理端分页响应。
     */
    private final AuditLogAdminService auditLogAdminService;

    /**
     * 分页查询审计日志；调用者或状态筛选有效时优先于时间范围。
     *
     * <p>时间参数按 ISO 本地日期时间绑定，只有起止时间同时提供才生效。调用者或状态的未知编码
     * 由服务按未提供处理。页码使用零基约定；当前默认每页 20 条的精确产品依据缺失，
     * 调大该值会增加单次数据库读取、DTO 映射和响应数据量，调小则会增加总页数和翻页请求。</p>
     *
     * @param caller    调用者 Agent 稳定编码，可选
     * @param status    审计状态稳定编码：ALLOWED、DENIED 或 FAILED，可选
     * @param startTime 创建时间下界，不携带时区
     * @param endTime   创建时间上界，不携带时区
     * @param page      零基页码，默认 {@code 0}
     * @param size      每页记录数，当前默认 {@code 20}，精确取值依据缺失
     * @return 统一响应包装的审计日志分页数据
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
        // 将管理员给出的筛选条件交给服务按固定优先级查询；绑定或分页非法时异常交由统一处理器返回。
        return ApiResponse.success(auditLogAdminService.listAuditLogs(
                caller, status, startTime, endTime, page, size));
    }
}
