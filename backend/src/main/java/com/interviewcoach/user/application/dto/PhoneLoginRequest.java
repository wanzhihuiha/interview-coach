package com.interviewcoach.user.application.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 公开手机号登录接口接收的验证码登录请求。
 *
 * <p>验证码校验成功后会从 Redis 删除；若手机号尚无账号，认证服务会自动创建账号和空白资料，
 * 再返回登录响应。</p>
 */
@Data
public class PhoneLoginRequest {

    /**
     * 用于校验验证码并查询账号的手机号；不存在对应账号时也作为新账号的绑定手机号。
     */
    @NotBlank(message = "手机号不能为空")
    private String phone;

    /**
     * 用户回传的一次性明文验证码，属于敏感认证数据，不应记录或展示。
     */
    @NotBlank(message = "验证码不能为空")
    private String smsCode;
}
