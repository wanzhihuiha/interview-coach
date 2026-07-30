package com.interviewcoach.resume.application.dto;

import java.time.LocalDateTime;
import lombok.Data;

/**
 * 简历列表项。
 */
@Data
public class ResumeListItemResponse {

    private Long resumeId;
    private String fileName;
    private String status;
    private String statusLabel;
    private String jobCategory;
    private String jobCategoryLabel;
    private Boolean hasConfirmedProfile;
    private String parseErrorMessage;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
