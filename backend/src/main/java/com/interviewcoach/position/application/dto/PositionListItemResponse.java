package com.interviewcoach.position.application.dto;

import java.time.LocalDateTime;
import lombok.Data;

/**
 * 岗位列表项响应。
 */
@Data
public class PositionListItemResponse {

    private Long positionId;
    private String positionName;
    private String companyName;
    private String jobCategory;
    private String level;
    private String jdContent;
    private String parseStatus;
    private String auditStatus;
    private Boolean isPublic;
    private Long userId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
