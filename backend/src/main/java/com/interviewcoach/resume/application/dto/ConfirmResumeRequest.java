package com.interviewcoach.resume.application.dto;

import com.interviewcoach.resume.domain.model.UserProfileData;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 确认简历解析结果请求。
 */
@Data
public class ConfirmResumeRequest {

    @NotNull(message = "画像数据不能为空")
    @Valid
    private UserProfileData profile;
}
