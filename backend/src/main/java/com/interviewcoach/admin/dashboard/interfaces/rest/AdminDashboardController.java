package com.interviewcoach.admin.dashboard.interfaces.rest;

import com.interviewcoach.admin.dashboard.application.dto.AdminDashboardStatsResponse;
import com.interviewcoach.admin.dashboard.application.service.AdminDashboardService;
import com.interviewcoach.common.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理后台首页概览统计的 HTTP 入口。
 *
 * <p>类级权限表达式要求当前 JWT 安全上下文包含 ADMIN 角色；入口不接收统计口径，
 * 只将服务计算的当前数据包装为统一响应。</p>
 */
@RestController
@RequestMapping("/api/v1/admin/dashboard")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminDashboardController {

    /**
     * 聚合管理首页需要的岗位、题库、用户和审计统计。
     */
    private final AdminDashboardService adminDashboardService;

    /**
     * 获取管理后台首页当前概览统计数据。
     *
     * @return 统一响应包装的四项统计值；仓储异常由统一异常处理链路处理
     */
    @GetMapping("/stats")
    public ApiResponse<AdminDashboardStatsResponse> getStats() {
        // 由服务在只读事务中查询各业务表，Controller 只负责 HTTP 响应包装。
        return ApiResponse.success(adminDashboardService.getStats());
    }
}
