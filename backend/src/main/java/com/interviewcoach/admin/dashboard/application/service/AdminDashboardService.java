package com.interviewcoach.admin.dashboard.application.service;

import com.interviewcoach.admin.dashboard.application.dto.AdminDashboardStatsResponse;
import com.interviewcoach.common.security.agent.AgentAuditLogRepository;
import com.interviewcoach.interview.domain.repository.TemporaryQuestionBankRepository;
import com.interviewcoach.position.domain.entity.PositionAnalysisTaskStatus;
import com.interviewcoach.position.domain.model.PositionAnalysisQueueOwner;
import com.interviewcoach.position.domain.repository.PositionAnalysisTaskRepository;
import com.interviewcoach.user.domain.repository.UserRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 管理后台首页的聚合统计服务。
 *
 * <p>由受 ADMIN 角色保护的概览接口调用，在只读事务中分别读取四个仓储并组装统计响应；
 * 各数字保持其当前数据库查询口径，不推导其他状态或业务总量。</p>
 */
@Service
@RequiredArgsConstructor
public class AdminDashboardService {

    /**
     * 统计公共队列中已解析成功、等待确认的当前岗位任务。
     */
    private final PositionAnalysisTaskRepository positionAnalysisTaskRepository;

    /**
     * 统计仍处于 PENDING 状态的临时题目。
     */
    private final TemporaryQuestionBankRepository temporaryQuestionBankRepository;

    /**
     * 统计用户表全部记录。
     */
    private final UserRepository userRepository;

    /**
     * 按创建时间范围统计 Agent 工具调用审计记录。
     */
    private final AgentAuditLogRepository agentAuditLogRepository;

    /**
     * 获取管理后台首页的四项当前统计数据。
     *
     * @return 按当前任务状态、题目状态、用户总表和应用本地日期口径组装的统计值
     */
    @Transactional(readOnly = true)
    public AdminDashboardStatsResponse getStats() {
        // 只统计公共队列中当前状态为 SUCCEEDED 的任务，其他公共任务状态不计入待确认数。
        long pendingPublicPositions = positionAnalysisTaskRepository.countByQueueOwnerAndStatus(
                PositionAnalysisQueueOwner.PUBLIC,
                PositionAnalysisTaskStatus.SUCCEEDED);
        // 只统计临时题库中状态字符串精确为 PENDING 的记录。
        long pendingQuestions = temporaryQuestionBankRepository.countByStatus("PENDING");
        // 用户总数直接来自用户表全量计数，不附加状态或角色条件。
        long totalUsers = userRepository.count();
        // 今日审计数使用应用进程本地日期边界查询审计日志表。
        long todayAudits = countTodayAudits();

        return new AdminDashboardStatsResponse(
                pendingPublicPositions, pendingQuestions, totalUsers, todayAudits);
    }

    /**
     * 按应用进程本地日期构造今天的最小和最大时间，并统计该闭区间内的审计记录。
     */
    private long countTodayAudits() {
        LocalDateTime startOfDay = LocalDateTime.of(LocalDate.now(), LocalTime.MIN);
        LocalDateTime endOfDay = LocalDateTime.of(LocalDate.now(), LocalTime.MAX);
        // 仓储按 createdAt 的 Between 语义统计起止边界内记录。
        return agentAuditLogRepository.countByCreatedAtBetween(startOfDay, endOfDay);
    }
}
