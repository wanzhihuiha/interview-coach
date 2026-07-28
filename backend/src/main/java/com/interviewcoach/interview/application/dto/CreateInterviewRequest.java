package com.interviewcoach.interview.application.dto;

import java.util.List;
import lombok.Data;

/**
 * 创建面试请求。
 */
@Data
public class CreateInterviewRequest {

    private Long resumeId;
    private Long positionId;
    private List<String> selectedPhases;
}
