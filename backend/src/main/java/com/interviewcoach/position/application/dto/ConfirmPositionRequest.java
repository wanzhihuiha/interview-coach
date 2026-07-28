package com.interviewcoach.position.application.dto;

import com.interviewcoach.position.domain.model.PositionProfileData;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 确认岗位解析结果请求。
 */
@Data
public class ConfirmPositionRequest {

    @NotNull(message = "画像数据不能为空")
    @Valid
    private PositionProfileData profile;
}
