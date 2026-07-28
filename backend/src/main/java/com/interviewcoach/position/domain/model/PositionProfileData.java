package com.interviewcoach.position.domain.model;

import java.util.List;
import lombok.Data;

/**
 * 岗位画像数据模型。
 */
@Data
public class PositionProfileData {

    private BasicInfo basicInfo;
    private List<SkillItem> requiredSkills;
    private List<SkillItem> preferredSkills;
    private List<ProbingDirection> probingDirections;
    private List<String> interviewFocus;
    private Double confidenceLevel;

    @Data
    public static class BasicInfo {
        private String title;
        private String company;
        private String location;
        private String level;
        private String salaryRange;
    }

    @Data
    public static class SkillItem {
        private String skill;
        private String importance;
        private String depth;
    }

    @Data
    public static class ProbingDirection {
        private String direction;
        private Integer priority;
        private String depthRange;
        private List<String> sampleQuestions;
    }

    /**
     * 创建空画像，用于 LLM 解析失败时的降级。
     */
    public static PositionProfileData empty() {
        PositionProfileData data = new PositionProfileData();
        data.setBasicInfo(new BasicInfo());
        data.setRequiredSkills(List.of());
        data.setPreferredSkills(List.of());
        data.setProbingDirections(List.of());
        data.setInterviewFocus(List.of());
        data.setConfidenceLevel(0.0);
        return data;
    }
}
