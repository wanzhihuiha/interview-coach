package com.interviewcoach.interview.application.dto;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import lombok.Data;

/**
 * 面试详情响应。
 */
@Data
public class InterviewDetailResponse {

    private Long interviewId;
    private Long resumeId;
    private Long positionId;
    private String status;
    private String statusLabel;
    private String currentPhase;
    private String currentPhaseLabel;
    private String currentTopic;
    private Integer currentDepth;
    private Integer totalQuestionCount;
    private List<String> selectedPhases;
    private Map<String, String> phaseLabels;
    private String pendingQuestion;
    private String positionTitle;
    private String companyName;
    private String jobCategory;
    private Integer overallScore;
    private String grade;
    private LocalDateTime startedAt;
    private LocalDateTime endedAt;
}
