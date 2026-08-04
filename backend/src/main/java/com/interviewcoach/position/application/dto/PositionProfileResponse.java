package com.interviewcoach.position.application.dto;

import com.interviewcoach.position.domain.model.PositionProfileData;
import lombok.Data;

/**
 * 岗位画像响应。
 */
@Data
public class PositionProfileResponse {

    private Long profileId;
    private Long positionId;
    private PositionProfileData profile;
    private Long taskId;
    private String latestTaskStatus;
    private String latestTaskStatusLabel;
    private PositionProfileData candidateProfile;
    private String analysisErrorCode;
    private String analysisErrorMessage;
    private Boolean profileUsable;
    private Boolean canConfirm;
    private Boolean canRetry;
    private Boolean archived;
}
