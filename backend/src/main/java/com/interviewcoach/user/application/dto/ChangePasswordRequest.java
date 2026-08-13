package com.interviewcoach.user.application.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 已认证用户提交的密码修改请求。
 *
 * <p>Controller 使用安全上下文中的用户身份，将三个明文密码值交给资料服务完成原密码校验、
 * 新密码规则校验和 BCrypt 加密；这些字段均为敏感数据，不应记录、回显或持久化为明文。</p>
 */
@Data
public class ChangePasswordRequest {

    /**
     * 用于确认当前账号持有权的现有明文密码。
     */
    @NotBlank(message = "原密码不能为空")
    private String oldPassword;

    /**
     * 通过格式校验后将被 BCrypt 加密并替换现有密码的新明文密码。
     */
    @NotBlank(message = "新密码不能为空")
    private String newPassword;

    /**
     * 用于和新密码逐字比较的确认值，本身不参与持久化。
     */
    @NotBlank(message = "确认密码不能为空")
    private String confirmPassword;
}
