package com.interviewcoach.admin.audit.application.service;

import com.interviewcoach.admin.audit.application.dto.AuditLogItemResponse;
import com.interviewcoach.admin.audit.application.dto.AuditLogListResponse;
import com.interviewcoach.common.security.agent.AgentAuditLogRecord;
import com.interviewcoach.common.security.agent.AgentAuditLogRepository;
import com.interviewcoach.common.security.agent.AgentType;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 管理端审计日志查询服务。
 *
 * <p>由受 ADMIN 角色保护的 HTTP 入口调用，按既定筛选优先级读取审计日志实体，
 * 再转换为同时携带稳定编码和中文名称的分页响应；本服务自身不重复校验管理员角色。</p>
 */
@Service
@RequiredArgsConstructor
public class AuditLogAdminService {

    /**
     * 提供审计日志的条件分页查询和总数统计元数据。
     */
    private final AgentAuditLogRepository auditLogRepository;

    /**
     * 按固定优先级分页查询审计日志。
     *
     * <p>筛选顺序依次为“调用者+状态”“调用者”“状态”“完整起止时间”“无筛选”。
     * 因而调用者或状态命中时会忽略时间条件，时间条件也只有起止值同时存在时才生效。
     * 非法调用者或状态编码会被当作未提供，而不是返回参数错误。</p>
     *
     * @param caller    Agent 类型编码，转换为大写后匹配；空白或非法值按未提供处理
     * @param status    审计状态编码，转换为大写后匹配；空白或非法值按未提供处理
     * @param startTime 创建时间下界，仅与 {@code endTime} 同时存在时参与查询
     * @param endTime   创建时间上界，仅与 {@code startTime} 同时存在时参与查询
     * @param page      零基页码
     * @param size      每页记录数
     * @return 当前页数据及仓储返回的分页元数据
     */
    @Transactional(readOnly = true)
    public AuditLogListResponse listAuditLogs(String caller, String status,
                                              LocalDateTime startTime, LocalDateTime endTime,
                                              int page, int size) {
        // 将零基页码和页大小交给 Spring Data；非法分页值由 PageRequest 直接拒绝。
        Pageable pageable = PageRequest.of(page, size);
        Page<AgentAuditLogRecord> recordPage;

        // 先把可识别的稳定编码转换为实体枚举，无法识别的值按未筛选处理。
        AgentType callerEnum = parseCaller(caller);
        AgentAuditLogRecord.AuditStatus statusEnum = parseStatus(status);

        if (callerEnum != null && statusEnum != null) {
            // 调用者与状态同时有效时使用最具体的组合查询，并忽略时间条件。
            recordPage = auditLogRepository.findByCallerAndStatus(callerEnum, statusEnum, pageable);
        } else if (callerEnum != null) {
            // 仅调用者有效时按调用者查询，并忽略状态原值和时间条件。
            recordPage = auditLogRepository.findByCaller(callerEnum, pageable);
        } else if (statusEnum != null) {
            // 仅状态有效时按状态查询，并忽略调用者原值和时间条件。
            recordPage = auditLogRepository.findByStatus(statusEnum, pageable);
        } else if (startTime != null && endTime != null) {
            // 只有完整起止时间存在时才按创建时间闭区间分页查询。
            recordPage = auditLogRepository.findByCreatedAtBetween(startTime, endTime, pageable);
        } else {
            // 没有可用枚举筛选且时间范围不完整时退化为全量分页查询。
            recordPage = auditLogRepository.findAll(pageable);
        }

        AuditLogListResponse response = new AuditLogListResponse();
        // 将当前页实体转换为管理端 DTO，同时保留仓储计算的总数、总页数和实际页码。
        response.setContent(recordPage.getContent().stream().map(this::toItem).toList());
        response.setTotalElements(recordPage.getTotalElements());
        response.setTotalPages(recordPage.getTotalPages());
        response.setCurrentPage(recordPage.getNumber());
        return response;
    }

    /**
     * 将调用者筛选值转换为 Agent 枚举；空白或未知编码返回 {@code null}，供主查询按未提供处理。
     */
    private AgentType parseCaller(String caller) {
        if (caller == null || caller.isBlank()) {
            return null;
        }
        try {
            return AgentType.valueOf(caller.toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * 将状态筛选值转换为审计状态枚举；空白或未知编码返回 {@code null}，供主查询按未提供处理。
     */
    private AgentAuditLogRecord.AuditStatus parseStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        try {
            return AgentAuditLogRecord.AuditStatus.valueOf(status.toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * 把持久化审计记录转换为管理端列表项，枚举同时输出稳定编码和中文名称。
     */
    private AuditLogItemResponse toItem(AgentAuditLogRecord record) {
        AuditLogItemResponse item = new AuditLogItemResponse();
        item.setId(record.getId());
        // 编码供筛选和程序判断，Label 直接供管理端展示；实体值为空时两者都保持为空。
        item.setCaller(record.getCaller() != null ? record.getCaller().name() : null);
        item.setCallerLabel(record.getCaller() != null ? record.getCaller().getDisplayName() : null);
        item.setOperation(record.getOperation());
        item.setMethodKey(record.getMethodKey());
        // 状态沿用与调用者相同的“稳定编码+中文名称”返回约定。
        item.setStatus(record.getStatus() != null ? record.getStatus().name() : null);
        item.setStatusLabel(record.getStatus() != null ? record.getStatus().getDisplayName() : null);
        item.setDurationMs(record.getDurationMs());
        item.setArgsSummary(record.getArgsSummary());
        item.setResultSummary(record.getResultSummary());
        item.setErrorMessage(record.getErrorMessage());
        item.setCreatedAt(record.getCreatedAt());
        return item;
    }
}
