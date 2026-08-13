package com.interviewcoach.admin.user.application.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 管理员更新目标用户账号状态的 HTTP 请求体。
 *
 * <p>由受 ADMIN 角色保护的用户管理接口接收并交给应用服务解析；本对象只携带目标状态，
 * 不携带操作者身份或目标用户 ID。</p>
 */
@Data
public class UpdateUserStatusRequest {

    /**
     * 目标账号状态稳定编码，当前接受 ACTIVE 或 DISABLED，服务转换前会统一为大写。
     * 空白值由 Bean Validation 返回参数校验错误，其他未知非空编码由服务返回业务错误码 6001。
     */
    @NotBlank(message = "用户状态不能为空")
    private String status;
}
