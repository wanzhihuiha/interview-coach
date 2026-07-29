package com.interviewcoach.interview.application.dto;

import java.util.List;
import java.util.Map;
import lombok.Data;

/**
 * 面试评估报告响应。
 */
@Data
public class InterviewReportResponse {

    private Long interviewId;
    private Integer overallScore;
    private String grade;
    private Map<String, PhaseSummary> phases;
    private DimensionScores dimensions;
    private List<String> strengths;
    private List<String> weaknesses;
    private List<String> keyEvents;
    private String conclusion;
    private String mdContent;

    @Data
    public static class PhaseSummary {
        private String phaseLabel;
        private Boolean completed;
        private Integer questionCount;
    }

    @Data
    public static class DimensionScores {
        private Integer technicalDepth;
        private Integer technicalBreadth;
        private Integer practicalExperience;
        private Integer expression;
        private Integer learningAbility;
    }
}
