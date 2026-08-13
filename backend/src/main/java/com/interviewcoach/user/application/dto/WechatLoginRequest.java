package com.interviewcoach.user.application.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 公开微信登录入口接收的模拟登录请求。
 *
 * <p>当前 code 只作为本地确定性派生模拟 OpenID 的输入，不会发送给微信 API，
 * 也不证明调用方持有真实微信身份。</p>
 */
@Data
public class WechatLoginRequest {

    /**
     * 用于派生模拟 OpenID 的非空字符串；当前实现不向微信服务校验其真实性或有效期。
     */
    @NotBlank(message = "微信授权码不能为空")
    private String code;
}
