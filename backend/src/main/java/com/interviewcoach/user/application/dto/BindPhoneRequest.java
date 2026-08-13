package com.interviewcoach.user.application.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 已认证用户提交的手机号绑定请求。
 *
 * <p>Controller 从受保护的 HTTP 接口接收本对象，并把目标手机号和一次性验证码交给资料服务校验；
 * 验证码匹配成功后，短信服务会删除对应的 Redis 验证码记录。</p>
 */
@Data
public class BindPhoneRequest {

    /**
     * 准备绑定到当前认证账号的手机号，也是查找 Redis 验证码时使用的业务标识。
     */
    @NotBlank(message = "手机号不能为空")
    private String phone;

    /**
     * 用户收到并回传的一次性明文验证码，属于敏感认证数据，不应记录或展示。
     */
    @NotBlank(message = "验证码不能为空")
    private String smsCode;
}
