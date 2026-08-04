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
    private Boolean isPublic;
    private Long userId;
    private Boolean archived;
    private LocalDateTime archivedAt;
    private Long latestTaskId;
    private String latestTaskStatus;
    private String latestTaskStatusLabel;
    private Boolean profileUsable;
    private Boolean canConfirm;
    private Boolean canRetry;
    private String analysisErrorCode;
    private String analysisErrorMessage;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
