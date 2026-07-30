package com.interviewcoach.resume.application.service;

import com.interviewcoach.common.exception.BusinessException;
import com.interviewcoach.resume.domain.model.UserProfileData;
import org.springframework.stereotype.Component;

/**
 * 校验事实画像是否具备可供确认和面试使用的最小内容。
 */
@Component
public class ResumeProfileValidator {

    /**
     * 校验正式画像至少包含一类可核对事实；草稿保存阶段不调用此校验。
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
