package com.interviewcoach.admin.user.interfaces.rest;

import com.interviewcoach.admin.user.application.dto.UpdateUserStatusRequest;
import com.interviewcoach.admin.user.application.dto.UserAdminListResponse;
import com.interviewcoach.admin.user.application.service.UserAdminService;
import com.interviewcoach.common.response.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户管理后台接口：管理员查看用户列表、启用/禁用用户。
 */
@RestController
@RequestMapping("/api/v1/admin/users")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class UserAdminController {

    private final UserAdminService userAdminService;

    /**
     * 分页查询用户列表。
     */
    @GetMapping
    public ApiResponse<UserAdminListResponse> list(
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "20") int size) {
        return ApiResponse.success(userAdminService.listUsers(page, size));
    }

    /**
     * 更新用户状态（启用/禁用）。
     */
    @PutMapping("/{id}/status")
    public ApiResponse<Void> updateStatus(
            @PathVariable("id") Long userId,
            @Valid @RequestBody UpdateUserStatusRequest request) {
        userAdminService.updateUserStatus(userId, request);
        return ApiResponse.success();
    }
}
