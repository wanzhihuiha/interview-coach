package com.interviewcoach.position.application.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 审核岗位请求。
 */
@Data
public class AuditPositionRequest {

    @NotBlank(message = "审核状态不能为空")
    private String status;

    private String remark;
}
