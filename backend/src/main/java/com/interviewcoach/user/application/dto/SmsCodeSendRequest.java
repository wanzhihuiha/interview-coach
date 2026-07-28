package com.interviewcoach.user.application.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 发送短信验证码请求。
 */
@Data
public class SmsCodeSendRequest {

    @NotBlank(message = "手机号不能为空")
    private String phone;
}
