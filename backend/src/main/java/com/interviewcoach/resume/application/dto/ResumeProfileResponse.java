package com.interviewcoach.resume.application.dto;

import com.interviewcoach.resume.domain.model.ResumeProfileAnalysisData;
import com.interviewcoach.resume.domain.model.UserProfileData;
import lombok.Data;

/**
 * 简历事实草稿、正式画像及可选 AI 分析的聚合响应。
 */
@Data
public class ResumeProfileResponse {

    private Long profileId;
    private Long resumeId;
    /**
     * 兼容字段：有草稿时返回草稿，否则返回已确认画像。
     */
    private UserProfileData profile;
    private UserProfileData confirmedProfile;
    private UserProfileData draftProfile;
    private ResumeProfileAnalysisData analysis;
    private Boolean hasConfirmedProfile;
    private Long parseGeneration;
    private String experienceLevel;
    private String experienceLevelLabel;
    private String status;
    private String statusLabel;
    private String analysisStatus;
    private String analysisErrorMessage;
    private Boolean analysisUsableForInterview;
    private Boolean analysisRefineAllowed;
    private Long analysisTaskGeneration;
    private String analysisMode;
}
