package com.interviewcoach.resume.domain.model;

import java.util.List;
import java.util.Map;
import lombok.Data;

/**
 * 用户画像数据模型。
 */
@Data
public class UserProfileData {

    private BasicInfo basicInfo;
    private List<String> skillTags;
    private Map<String, String> skillLevel;
    private List<ProjectExperience> projectExperience;
    private List<WorkExperience> workExperience;
    private List<String> strengths;
    private List<String> weaknesses;
    private Double confidenceLevel;
    private String experienceLevel;

    @Data
    public static class BasicInfo {
        private String name;
        private String age;
        private String gender;
        private String workingYears;
        private String currentPosition;
        private String education;
    }

    @Data
    public static class ProjectExperience {
        private String name;
        private String role;
        private List<String> techStack;
        private String description;
    }

    @Data
    public static class WorkExperience {
        private String company;
        private String position;
        private String duration;
        private List<String> highlights;
    }

    /**
     * 创建空画像，用于 LLM 解析失败时的降级。
     */
    public static UserProfileData empty() {
        UserProfileData data = new UserProfileData();
        data.setBasicInfo(new BasicInfo());
        data.setSkillTags(List.of());
        data.setSkillLevel(Map.of());
        data.setProjectExperience(List.of());
        data.setWorkExperience(List.of());
        data.setStrengths(List.of());
        data.setWeaknesses(List.of());
        data.setConfidenceLevel(0.0);
        return data;
    }
}
