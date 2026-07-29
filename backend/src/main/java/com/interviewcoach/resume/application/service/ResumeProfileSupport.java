package com.interviewcoach.resume.application.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.interviewcoach.common.exception.BusinessException;
import com.interviewcoach.resume.domain.agent.ResumeAnalysisAgent;
import com.interviewcoach.resume.domain.entity.ResumeProfile;
import com.interviewcoach.resume.domain.model.UserProfileData;
import com.interviewcoach.resume.domain.repository.ResumeProfileRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 简历画像写入与派生字段计算的共享组件，供后台解析完成和用户确认两条路径复用。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ResumeProfileSupport {

    private final ResumeProfileRepository resumeProfileRepository;
    private final ResumeAnalysisAgent resumeAnalysisAgent;
    private final ObjectMapper objectMapper;

    /**
     * 在调用方事务中新增或覆盖指定简历的画像。
     * 本组件不自行开启事务，由调用方保证画像与简历状态、岗位类别的原子更新。
     */
    public void saveProfile(Long resumeId, Long userId, UserProfileData data) {
        String json;
        try {
            json = objectMapper.writeValueAsString(data);
        } catch (JsonProcessingException e) {
            throw new BusinessException(ResumeErrorCode.PROFILE_DATA_INVALID, "画像数据序列化失败", e);
        }

        ResumeProfile profile = resumeProfileRepository.findByResumeId(resumeId)
                .orElseGet(() -> {
                    ResumeProfile newProfile = new ResumeProfile();
                    newProfile.setResumeId(resumeId);
                    newProfile.setUserId(userId);
                    return newProfile;
                });
        boolean creating = profile.getId() == null;
        profile.setProfileData(json);
        profile.setExperienceLevel(resumeAnalysisAgent.inferExperienceLevel(data));
        ResumeProfile savedProfile = resumeProfileRepository.save(profile);
        log.info("[ResumeProfile] 画像已写入当前事务: resumeId={}, profileId={}, operation={}",
                resumeId, savedProfile.getId(), creating ? "CREATE" : "UPDATE");
    }

    /**
     * 根据画像技能推断岗位类别；没有技能或无法识别时沿用技术类作为兼容默认值。
     */
    public String inferJobCategory(UserProfileData data) {
        if (data == null || data.getSkillTags() == null) {
            return "TECH";
        }
        String text = String.join(" ", data.getSkillTags()).toLowerCase();
        if (text.contains("java") || text.contains("python") || text.contains("前端")
                || text.contains("后端") || text.contains("测试") || text.contains("运维")) {
            return "TECH";
        }
        if (text.contains("产品") || text.contains("需求") || text.contains("prd")) {
            return "PRODUCT";
        }
        if (text.contains("设计") || text.contains("ui") || text.contains("ux")) {
            return "DESIGN";
        }
        return "TECH";
    }
}
