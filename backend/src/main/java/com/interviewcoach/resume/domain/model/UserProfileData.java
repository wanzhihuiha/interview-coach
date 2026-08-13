package com.interviewcoach.resume.domain.model;

import java.util.List;
import java.util.Map;
import lombok.Data;

/**
 * 用于承载可核对简历事实的数据结构；模型提取结果先成为草稿，只有用户确认后才作为面试使用的正式画像。
 * 字段契约不接收辅助分析的模型推断结论。
 */
@Data
public class UserProfileData {

    /** 候选人的基础事实信息；没有对应原文时字段可为空。 */
    private BasicInfo basicInfo;
    /** 简历原文明示的技能标签集合；没有技能时为空列表。 */
    private List<String> skillTags;
    /** 技能名称到原文明示水平的映射；没有明确水平时不应凭模型补写。 */
    private Map<String, String> skillLevel;
    /** 简历原文中的项目经历，按输入顺序供确认和面试选题使用。 */
    private List<ProjectExperience> projectExperience;
    /** 简历原文中的工作经历，按输入顺序供确认和面试选题使用。 */
    private List<WorkExperience> workExperience;

    /** 事实画像中的基础信息，只保存原文可核对的工作年限、当前职位和学历。 */
    @Data
    public static class BasicInfo {
        /** 简历原文明示的工作年限文本；没有对应原文时为空。 */
        private String workingYears;
        /** 简历原文明示的当前职位；没有时为空。 */
        private String currentPosition;
        /** 简历原文明示的学历信息；没有时为空。 */
        private String education;
    }

    /** 一段项目事实经历，由模型提取或用户编辑后进入草稿。 */
    @Data
    public static class ProjectExperience {
        /** 项目名称；原文未给出时可为空。 */
        private String name;
        /** 候选人在项目中的明确角色。 */
        private String role;
        /** 项目原文明示的技术栈；没有时为空列表。 */
        private List<String> techStack;
        /** 项目职责或成果的事实描述。 */
        private String description;
    }

    /** 一段工作事实经历，由模型提取或用户编辑后进入草稿。 */
    @Data
    public static class WorkExperience {
        /** 简历原文明示的公司名称；发送模型前的原文可能已经过尽力脱敏。 */
        private String company;
        /** 该段经历中的职位名称。 */
        private String position;
        /** 原文给出的任职时间范围或时长文本。 */
        private String duration;
        /** 该段工作经历的明确职责或成果；没有时为空列表。 */
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
