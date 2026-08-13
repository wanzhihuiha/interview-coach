package com.interviewcoach.user.application.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 公开 Mock 短信验证码接口接收的请求。
 *
 * <p>当前实现以手机号拼接 Redis 验证码和频控 Key，并把生成的明文验证码放入 HTTP 响应；
 * 本对象不表示已经接入真实短信网关。</p>
 */
@Data
public class SmsCodeSendRequest {

    /**
     * 生成验证码时使用的目标手机号，也是当前 Redis Key 作用域和响应中返回的手机号。
     */
    @NotBlank(message = "手机号不能为空")
    private String phone;
}
