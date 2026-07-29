package com.interviewcoach.resume.application.dto;

import com.interviewcoach.resume.domain.model.UserProfileData;
import lombok.Data;

/**
 * 简历画像响应。
 */
@Data
public class ResumeProfileResponse {

    private Long profileId;
    private Long resumeId;
    private UserProfileData profile;
    private String experienceLevel;
    private String experienceLevelLabel;
    private String status;
    private String statusLabel;
}
