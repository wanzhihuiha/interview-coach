package com.interviewcoach.user.application.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 公开注册接口接收的用户名注册请求。
 *
 * <p>用户名和密码是必填项；确认密码、手机号和短信验证码按当前实现保持可选，
 * Controller 将这些值原样交给认证服务完成条件校验和账号创建。</p>
 */
@Data
public class RegisterRequest {

    /**
     * 新账号用户名，认证服务要求为 4～20 位 ASCII 字母、数字或下划线。
     */
    @NotBlank(message = "用户名不能为空")
    private String username;

    /**
     * 新账号明文密码，通过规则校验后仅保存 BCrypt 密文；该值不应记录或回显。
     */
    @NotBlank(message = "密码不能为空")
    private String password;

    /**
     * 新密码确认值；为 {@code null}、空串或纯空白时，当前注册流程不会执行一致性比较。
     */
    private String confirmPassword;

    /**
     * 可选绑定手机号；为 {@code null}、空串或纯空白时，当前注册流程跳过手机号和短信校验。
     */
    private String phone;

    /**
     * 可选手机号对应的一次性明文验证码；只有提交了非空手机号时才会被认证服务校验。
     */
    private String smsCode;
}
