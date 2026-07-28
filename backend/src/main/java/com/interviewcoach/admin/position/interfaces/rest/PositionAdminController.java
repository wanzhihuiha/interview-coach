package com.interviewcoach.admin.position.interfaces.rest;

import com.interviewcoach.common.response.ApiResponse;
import com.interviewcoach.position.application.dto.PositionDetailResponse;
import com.interviewcoach.position.application.dto.PositionListResponse;
import com.interviewcoach.position.application.service.PositionService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 岗位管理后台接口：管理员查看并审核所有用户上传的岗位。
 */
@RestController
@RequestMapping("/api/v1/admin/positions")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class PositionAdminController {

    private final PositionService positionService;

    /**
     * 查询所有用户上传的岗位（支持按审核状态筛选）。
     */
    @GetMapping
    public ApiResponse<PositionListResponse> listAllPositions(
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "10") int size,
            @RequestParam(value = "auditStatus", required = false) String auditStatus) {
        return ApiResponse.success(positionService.listAllPositions(page, size, auditStatus));
    }

    /**
     * 查询任意岗位详情。
     */
    @GetMapping("/{id}")
    public ApiResponse<PositionDetailResponse> getPositionDetail(
            @PathVariable("id") Long positionId) {
        return ApiResponse.success(positionService.getPositionDetailForAdmin(positionId));
    }
}
