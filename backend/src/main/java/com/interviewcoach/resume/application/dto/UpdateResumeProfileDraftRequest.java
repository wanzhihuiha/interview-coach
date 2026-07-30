package com.interviewcoach.resume.application.dto;

import com.interviewcoach.resume.domain.model.UserProfileData;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 保存用户编辑后的画像草稿请求。
 */
@Data
public class UpdateResumeProfileDraftRequest {

    @NotNull(message = "解析代次不能为空")
    private Long parseGeneration;

    @NotNull(message = "画像数据不能为空")
    @Valid
    private UserProfileData profile;
}
