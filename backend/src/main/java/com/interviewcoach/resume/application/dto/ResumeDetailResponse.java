package com.interviewcoach.resume.application.dto;

import com.interviewcoach.resume.domain.model.UserProfileData;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 简历详情响应。
 */
@Data
public class ResumeDetailResponse {

    private Long resumeId;
    private String fileName;
    private String fileType;
    private Long fileSize;
    private String status;
    private String statusLabel;
    private String jobCategory;
    private String jobCategoryLabel;
    private UserProfileData parsedData;
    private Boolean hasConfirmedProfile;
    private String parseErrorCode;
    private String parseErrorMessage;
    private LocalDateTime createdAt;
    private LocalDateTime confirmedAt;
}
