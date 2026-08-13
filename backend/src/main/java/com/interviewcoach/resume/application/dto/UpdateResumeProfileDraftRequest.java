package com.interviewcoach.resume.application.dto;

import com.interviewcoach.resume.domain.model.UserProfileData;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 保存用户编辑后的事实画像草稿请求。
 */
@Data
public class UpdateResumeProfileDraftRequest {

    /**
     * 用户开始编辑时读取到的解析代次；与服务端当前代次不一致时拒绝保存，避免覆盖新解析结果。
     */
    @NotNull(message = "解析代次不能为空")
    private Long parseGeneration;

    /** 用户编辑后的事实画像内容；保存草稿时执行规范化，正式画像的最小事实校验留到确认阶段。 */
    @NotNull(message = "画像数据不能为空")
    @Valid
    private UserProfileData profile;
}
