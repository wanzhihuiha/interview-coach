package com.interviewcoach.interview.application.dto;

import java.util.List;
import java.util.Map;
import lombok.Data;

/**
 * 创建面试响应。
 */
@Data
public class CreateInterviewResponse {

    private Long interviewId;
    private String status;
    private String statusLabel;
    private List<String> selectedPhases;
    private String currentPhase;
    private String currentPhaseLabel;
    private String firstQuestion;
    private List<String> phaseOrder;
    private Map<String, String> phaseLabels;
}
