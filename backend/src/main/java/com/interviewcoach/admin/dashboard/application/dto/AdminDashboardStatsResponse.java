package com.interviewcoach.admin.dashboard.application.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 管理后台概览统计数据响应。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AdminDashboardStatsResponse {

    /**
     * 等待管理员确认候选的公共岗位数量。
     */
    private long pendingPublicPositions;

    /**
     * 待审核题目数量。
     */
    private long pendingQuestions;

    /**
     * 用户总数。
     */
    private long totalUsers;

    /**
     * 今日审计日志数量。
     */
    private long todayAudits;
}
