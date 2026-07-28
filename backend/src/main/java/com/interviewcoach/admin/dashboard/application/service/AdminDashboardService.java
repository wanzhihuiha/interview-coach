package com.interviewcoach.admin.dashboard.application.service;

import com.interviewcoach.admin.dashboard.application.dto.AdminDashboardStatsResponse;
import com.interviewcoach.common.security.agent.AgentAuditLogRepository;
import com.interviewcoach.interview.domain.repository.TemporaryQuestionBankRepository;
import com.interviewcoach.position.domain.entity.PositionAuditStatus;
import com.interviewcoach.position.domain.repository.PositionRepository;
import com.interviewcoach.user.domain.repository.UserRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 管理后台概览统计服务。
 */
@Service
@RequiredArgsConstructor
public class AdminDashboardService {

    private final PositionRepository positionRepository;
    private final TemporaryQuestionBankRepository temporaryQuestionBankRepository;
    private final UserRepository userRepository;
    private final AgentAuditLogRepository agentAuditLogRepository;

    /**
     * 获取管理后台概览统计数据。
     */
    @Transactional(readOnly = true)
    public AdminDashboardStatsResponse getStats() {
        long pendingPositions = positionRepository.countByAuditStatus(PositionAuditStatus.PENDING);
        long pendingQuestions = temporaryQuestionBankRepository.countByStatus("PENDING");
        long totalUsers = userRepository.count();
        long todayAudits = countTodayAudits();

        return new AdminDashboardStatsResponse(pendingPositions, pendingQuestions, totalUsers, todayAudits);
    }

    private long countTodayAudits() {
        LocalDateTime startOfDay = LocalDateTime.of(LocalDate.now(), LocalTime.MIN);
        LocalDateTime endOfDay = LocalDateTime.of(LocalDate.now(), LocalTime.MAX);
        return agentAuditLogRepository.countByCreatedAtBetween(startOfDay, endOfDay);
    }
}
