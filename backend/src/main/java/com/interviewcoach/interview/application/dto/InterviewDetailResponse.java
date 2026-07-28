package com.interviewcoach.interview.application.dto;

import java.time.LocalDateTime;
import java.util.List;
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
    private String currentPhase;
    private String currentTopic;
    private Integer currentDepth;
    private Integer totalQuestionCount;
    private List<String> selectedPhases;
    private String pendingQuestion;
    private String positionTitle;
    private String companyName;
    private Integer overallScore;
    private String grade;
    private LocalDateTime startedAt;
    private LocalDateTime endedAt;
}
