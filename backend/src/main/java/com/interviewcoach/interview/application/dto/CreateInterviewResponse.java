package com.interviewcoach.interview.application.dto;

import java.util.List;
import lombok.Data;

/**
 * 创建面试响应。
 */
@Data
public class CreateInterviewResponse {

    private Long interviewId;
    private String status;
    private List<String> selectedPhases;
    private String currentPhase;
    private String firstQuestion;
    private List<String> phaseOrder;
}
