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
 * 审计日志管理后台服务。
 */
@Service
@RequiredArgsConstructor
public class AuditLogAdminService {

    private final AgentAuditLogRepository auditLogRepository;

    /**
     * 分页查询审计日志。
     */
    @Transactional(readOnly = true)
    public AuditLogListResponse listAuditLogs(String caller, String status,
                                              LocalDateTime startTime, LocalDateTime endTime,
                                              int page, int size) {
        Pageable pageable = PageRequest.of(page, size);
        Page<AgentAuditLogRecord> recordPage;

        AgentType callerEnum = parseCaller(caller);
        AgentAuditLogRecord.AuditStatus statusEnum = parseStatus(status);

        if (callerEnum != null && statusEnum != null) {
            recordPage = auditLogRepository.findByCallerAndStatus(callerEnum, statusEnum, pageable);
        } else if (callerEnum != null) {
            recordPage = auditLogRepository.findByCaller(callerEnum, pageable);
        } else if (statusEnum != null) {
            recordPage = auditLogRepository.findByStatus(statusEnum, pageable);
        } else if (startTime != null && endTime != null) {
            recordPage = auditLogRepository.findByCreatedAtBetween(startTime, endTime, pageable);
        } else {
            recordPage = auditLogRepository.findAll(pageable);
        }

        AuditLogListResponse response = new AuditLogListResponse();
        response.setContent(recordPage.getContent().stream().map(this::toItem).toList());
        response.setTotalElements(recordPage.getTotalElements());
        response.setTotalPages(recordPage.getTotalPages());
        response.setCurrentPage(recordPage.getNumber());
        return response;
    }

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

    private AuditLogItemResponse toItem(AgentAuditLogRecord record) {
        AuditLogItemResponse item = new AuditLogItemResponse();
        item.setId(record.getId());
        item.setCaller(record.getCaller() != null ? record.getCaller().name() : null);
        item.setOperation(record.getOperation());
        item.setMethodKey(record.getMethodKey());
        item.setStatus(record.getStatus() != null ? record.getStatus().name() : null);
        item.setDurationMs(record.getDurationMs());
        item.setArgsSummary(record.getArgsSummary());
        item.setResultSummary(record.getResultSummary());
        item.setErrorMessage(record.getErrorMessage());
        item.setCreatedAt(record.getCreatedAt());
        return item;
    }
}
