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
 * 管理后台概览接口。
 */
@RestController
@RequestMapping("/api/v1/admin/dashboard")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminDashboardController {

    private final AdminDashboardService adminDashboardService;

    /**
     * 获取管理后台概览统计数据。
     */
    @GetMapping("/stats")
    public ApiResponse<AdminDashboardStatsResponse> getStats() {
        return ApiResponse.success(adminDashboardService.getStats());
    }
}
