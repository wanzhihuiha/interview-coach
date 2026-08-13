package com.interviewcoach.user.application.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 公开登录接口接收的用户名密码请求。
 *
 * <p>Controller 将本对象交给认证服务查询账号、校验 BCrypt 密码并签发登录响应。</p>
 */
@Data
public class LoginRequest {

    /**
     * 用于查询登录账号的用户名。
     */
    @NotBlank(message = "用户名不能为空")
    private String username;

    /**
     * 用于和已保存 BCrypt 密文匹配的明文密码，属于敏感认证数据，不应记录或回显。
     */
    @NotBlank(message = "密码不能为空")
    private String password;
}
