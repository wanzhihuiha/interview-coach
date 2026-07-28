package com.interviewcoach.position.application.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 创建岗位响应。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PositionCreateResponse {

    private Long positionId;
    private String positionName;
    private String parseStatus;
    private String auditStatus;
}
