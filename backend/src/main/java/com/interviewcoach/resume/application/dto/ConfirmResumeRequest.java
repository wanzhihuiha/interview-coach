package com.interviewcoach.resume.application.dto;

import com.interviewcoach.resume.domain.model.UserProfileData;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 用户把当前解析草稿确认为正式事实画像的 HTTP 请求。
 */
@Data
public class ConfirmResumeRequest {

    /**
     * 前端读取草稿时获得的解析代次；旧客户端未传时兼容使用服务端当前代次。
     */
    private Long parseGeneration;

    /**
     * 用户确认后的事实画像；应用服务会重新规范化、校验并保存，不能把辅助分析写入该对象。
     */
    @NotNull(message = "画像数据不能为空")
    @Valid
    private UserProfileData profile;
}
