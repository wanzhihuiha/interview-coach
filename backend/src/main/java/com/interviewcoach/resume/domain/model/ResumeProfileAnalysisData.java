package com.interviewcoach.resume.domain.model;

import java.util.List;
import lombok.Data;

/**
 * 基于已确认简历事实生成的辅助分析，只用于面试选题和追问。
 */
@Data
public class ResumeProfileAnalysisData {

    private List<AnalysisItem> strengths;
    private List<AnalysisItem> verificationPoints;
    private List<SkillAssessment> skillAssessments;

    @Data
    public static class AnalysisItem {
        private String content;
        private List<String> evidenceRefs;
        private Double confidence;
    }

    @Data
    public static class SkillAssessment {
        private String skill;
        private String inferredLevel;
        private List<String> evidenceRefs;
        private Double confidence;
    }
}
