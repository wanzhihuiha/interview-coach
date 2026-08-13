package com.interviewcoach.resume.application.service;

import com.interviewcoach.common.exception.BusinessException;
import com.interviewcoach.resume.domain.model.UserProfileData;
import org.springframework.stereotype.Component;

/**
 * 由画像支持组件在草稿转为正式画像前调用，判断用户提交内容是否至少包含一类可核对事实。
 * 校验失败会抛简历模块业务异常并阻止正式画像、简历确认状态及后续辅助分析写入；草稿保存不使用本校验器。
 */
@Component
public class ResumeProfileValidator {

    /**
     * 接受明示基本信息、任一技能、任一有效项目字段或任一有效工作字段中的至少一类。
     * 输入为空或四类事实均为空时抛 PROFILE_DATA_INVALID，调用方事务保持原草稿和正式画像不变。
     */
    public void validate(UserProfileData data) {
        if (data == null) {
            throw new BusinessException(ResumeErrorCode.PROFILE_DATA_INVALID, "画像数据无效");
        }
        boolean hasSkills = data.getSkillTags() != null && !data.getSkillTags().isEmpty();
        boolean hasProjects = data.getProjectExperience() != null
                && data.getProjectExperience().stream().anyMatch(this::hasProjectFact);
        boolean hasWork = data.getWorkExperience() != null
                && data.getWorkExperience().stream().anyMatch(this::hasWorkFact);
        UserProfileData.BasicInfo basicInfo = data.getBasicInfo();
        boolean hasBasicInfo = basicInfo != null && (hasText(basicInfo.getWorkingYears())
                || hasText(basicInfo.getCurrentPosition()) || hasText(basicInfo.getEducation()));
        if (!hasBasicInfo && !hasSkills && !hasProjects && !hasWork) {
            throw new BusinessException(ResumeErrorCode.PROFILE_DATA_INVALID, "画像缺少可确认的简历事实");
        }
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private boolean hasProjectFact(UserProfileData.ProjectExperience project) {
        return project != null && (hasText(project.getName())
                || hasText(project.getRole())
                || hasText(project.getDescription())
                || project.getTechStack() != null && !project.getTechStack().isEmpty());
    }

    private boolean hasWorkFact(UserProfileData.WorkExperience work) {
        return work != null && (hasText(work.getCompany())
                || hasText(work.getPosition())
                || hasText(work.getDuration())
                || work.getHighlights() != null && !work.getHighlights().isEmpty());
    }
}
