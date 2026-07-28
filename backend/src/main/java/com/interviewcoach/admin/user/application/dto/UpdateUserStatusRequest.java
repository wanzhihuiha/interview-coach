package com.interviewcoach.admin.user.application.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 管理员更新用户状态请求。
 */
@Data
public class UpdateUserStatusRequest {

    /**
     * 目标状态：ACTIVE 或 DISABLED。
     */
    @NotBlank(message = "用户状态不能为空")
    private String status;
}
