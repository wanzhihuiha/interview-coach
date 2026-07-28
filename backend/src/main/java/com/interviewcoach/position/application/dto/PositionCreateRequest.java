package com.interviewcoach.position.application.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 创建岗位请求。
 */
@Data
public class PositionCreateRequest {

    @NotBlank(message = "岗位名称不能为空")
    private String positionName;

    private String companyName;
    private String location;
    private String salaryRange;

    @NotBlank(message = "岗位大类不能为空")
    private String jobCategory;

    private String level;

    @NotBlank(message = "JD 描述不能为空")
    private String jdContent;
}
