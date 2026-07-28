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
    private String parseStatus;
}
