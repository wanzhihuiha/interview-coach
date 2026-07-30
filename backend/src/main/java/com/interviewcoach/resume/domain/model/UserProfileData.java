package com.interviewcoach.resume.domain.model;

import java.util.List;
import java.util.Map;
import lombok.Data;

/**
 * 简历可核对事实数据，不包含姓名、年龄、性别或模型推断结论。
 */
@Data
public class UserProfileData {

    private BasicInfo basicInfo;
    private List<String> skillTags;
    private Map<String, String> skillLevel;
    private List<ProjectExperience> projectExperience;
    private List<WorkExperience> workExperience;

    @Data
    public static class BasicInfo {
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
     * 创建字段结构完整的空事实数据，便于表单和测试按需填充。
     */
    public static UserProfileData empty() {
        UserProfileData data = new UserProfileData();
        data.setBasicInfo(new BasicInfo());
        data.setSkillTags(List.of());
        data.setSkillLevel(Map.of());
        data.setProjectExperience(List.of());
        data.setWorkExperience(List.of());
        return data;
    }
}
