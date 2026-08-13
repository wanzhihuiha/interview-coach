package com.interviewcoach.admin.dashboard.application.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 管理后台首页概览统计响应。
 *
 * <p>四项数据由概览服务分别查询当前岗位任务、临时题库、用户和 Agent 审计日志表后组装，
 * 经 ADMIN 接口返回用于展示待处理量和基础规模。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AdminDashboardStatsResponse {

    /**
     * 当前任务表中队列归属为 PUBLIC 且状态为 SUCCEEDED 的记录数，即待管理员确认的公共岗位候选数。
     */
    private long pendingPublicPositions;

    /**
     * 临时题库中状态字符串精确等于 PENDING 的记录数。
     */
    private long pendingQuestions;

    /**
     * 用户表中的全部记录数，不按账号状态或角色过滤。
     */
    private long totalUsers;

    /**
     * 审计日志创建时间落在应用进程本地“今天”起止边界内的记录数。
     */
    private long todayAudits;
}
