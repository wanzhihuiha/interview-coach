package com.interviewcoach.position.application.dto;

import java.time.LocalDateTime;
import lombok.Data;

/**
 * 岗位详情响应。
 */
@Data
public class PositionDetailResponse {

    private Long positionId;
    private String positionName;
    private String companyName;
    private String location;
    private String salaryRange;
    private String jobCategory;
    private String jobCategoryLabel;
    private String level;
    private String levelLabel;
    private String jdContent;
    private String parseStatus;
    private String parseStatusLabel;
    private String auditStatus;
    private String auditStatusLabel;
    private Boolean isPublic;
    private Long userId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime auditedAt;
}
