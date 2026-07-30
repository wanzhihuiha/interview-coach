package com.interviewcoach.resume.application.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.interviewcoach.common.exception.BusinessException;
import com.interviewcoach.resume.domain.entity.ResumeProfile;
import com.interviewcoach.resume.domain.entity.ResumeProfileDraft;
import com.interviewcoach.resume.domain.model.UserProfileData;
import com.interviewcoach.resume.domain.repository.ResumeProfileDraftRepository;
import com.interviewcoach.resume.domain.repository.ResumeProfileRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 维护简历事实草稿和已确认画像；调用方负责简历状态与画像写入的事务边界。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ResumeProfileSupport {

    public static final int PROFILE_SCHEMA_VERSION = 2;

    private final ResumeProfileRepository resumeProfileRepository;
    private final ResumeProfileDraftRepository draftRepository;
    private final ResumeProfileNormalizer normalizer;
    private final ResumeProfileValidator validator;
    private final ObjectMapper objectMapper;

    /**
     * 保存模型提取出的当前代次草稿。草稿允许内容为空，最终完整性在确认时校验。
     */
    public ResumeProfileDraft saveDraft(Long resumeId, Long userId, Long generation, UserProfileData data) {
        UserProfileData normalized = normalizeDraft(data);
        ResumeProfileDraft draft = draftRepository.findByResumeIdAndUserId(resumeId, userId)
                .orElseGet(() -> {
                    ResumeProfileDraft created = new ResumeProfileDraft();
                    created.setResumeId(resumeId);
                    created.setUserId(userId);
                    return created;
                });
        draft.setProfileData(toJson(normalized));
        draft.setExperienceLevel(normalizer.inferExperienceLevel(normalized));
        draft.setParseGeneration(generation);
        draft.setSchemaVersion(PROFILE_SCHEMA_VERSION);
        return draftRepository.save(draft);
    }

    /**
     * 保存用户正在编辑的草稿；generation 不一致时拒绝覆盖较新的解析结果。
     */
    public ResumeProfileDraft updateDraft(Long resumeId, Long userId, Long generation, UserProfileData data) {
        ResumeProfileDraft draft = draftRepository.findByResumeIdAndUserId(resumeId, userId)
                .orElseThrow(() -> new BusinessException(
                        ResumeErrorCode.PROFILE_DRAFT_NOT_FOUND, "待确认画像不存在"));
        if (!generation.equals(draft.getParseGeneration())) {
            throw new BusinessException(ResumeErrorCode.PROFILE_DRAFT_STALE, "画像草稿已过期，请刷新后重试");
        }
        UserProfileData normalized = normalizeDraft(data);
        draft.setProfileData(toJson(normalized));
        draft.setExperienceLevel(normalizer.inferExperienceLevel(normalized));
        return draftRepository.save(draft);
    }

    /**
     * 将当前 generation 的草稿确认成正式画像，并删除已消费草稿。
     */
    public ConfirmedProfile confirmDraft(
            Long resumeId, Long userId, Long generation, UserProfileData confirmedData) {
        ResumeProfileDraft draft = draftRepository.findByResumeIdAndUserId(resumeId, userId)
                .orElseThrow(() -> new BusinessException(
                        ResumeErrorCode.PROFILE_DRAFT_NOT_FOUND, "待确认画像不存在"));
        if (!generation.equals(draft.getParseGeneration())) {
            throw new BusinessException(ResumeErrorCode.PROFILE_DRAFT_STALE, "画像草稿已过期，请刷新后重试");
        }

        UserProfileData normalized = normalizeAndValidate(confirmedData);
        String json = toJson(normalized);
        String profileHash = sha256(json);
        ResumeProfile profile = resumeProfileRepository.findByResumeIdAndUserId(resumeId, userId)
                .orElseGet(() -> {
                    ResumeProfile created = new ResumeProfile();
                    created.setResumeId(resumeId);
                    created.setUserId(userId);
                    return created;
                });
        profile.setProfileData(json);
        profile.setExperienceLevel(normalizer.inferExperienceLevel(normalized));
        profile.setProfileHash(profileHash);
        profile.setSchemaVersion(PROFILE_SCHEMA_VERSION);
        profile.setConfirmedAt(LocalDateTime.now());
        ResumeProfile saved = resumeProfileRepository.save(profile);
        draftRepository.delete(draft);
        log.info("[ResumeProfile] 正式画像已确认: resumeId={}, profileId={}", resumeId, saved.getId());
        return new ConfirmedProfile(saved, normalized, profileHash);
    }

    public Optional<UserProfileData> loadConfirmed(Long resumeId, Long userId) {
        return resumeProfileRepository.findByResumeIdAndUserId(resumeId, userId)
                .map(profile -> fromJson(resumeId, profile.getProfileData()));
    }

    public Optional<UserProfileData> loadDraft(Long resumeId, Long userId) {
        return draftRepository.findByResumeIdAndUserId(resumeId, userId)
                .map(draft -> fromJson(resumeId, draft.getProfileData()));
    }

    public UserProfileData fromJson(Long resumeId, String json) {
        if (json == null || json.isBlank()) {
            throw new BusinessException(ResumeErrorCode.PROFILE_DATA_INVALID, "画像数据为空");
        }
        try {
            return objectMapper.readValue(json, UserProfileData.class);
        } catch (JsonProcessingException e) {
            log.warn("[ResumeProfile] 画像 JSON 解析失败: resumeId={}, errorType={}",
                    resumeId, e.getClass().getSimpleName());
            throw new BusinessException(ResumeErrorCode.PROFILE_DATA_INVALID, "画像数据格式错误", e);
        }
    }

    public String inferJobCategory(UserProfileData data) {
        if (data == null || data.getSkillTags() == null) {
            return "TECH";
        }
        String text = String.join(" ", data.getSkillTags()).toLowerCase();
        if (text.contains("产品") || text.contains("需求") || text.contains("prd")) {
            return "PRODUCT";
        }
        if (text.contains("设计") || text.contains("ui") || text.contains("ux")) {
            return "DESIGN";
        }
        return "TECH";
    }

    private UserProfileData normalizeAndValidate(UserProfileData data) {
        UserProfileData normalized = normalizeDraft(data);
        validator.validate(normalized);
        return normalized;
    }

    private UserProfileData normalizeDraft(UserProfileData data) {
        if (data == null) {
            throw new BusinessException(ResumeErrorCode.PROFILE_DATA_INVALID, "画像数据无效");
        }
        return normalizer.normalize(data);
    }

    private String toJson(UserProfileData data) {
        try {
            return objectMapper.writeValueAsString(data);
        } catch (JsonProcessingException e) {
            throw new BusinessException(ResumeErrorCode.PROFILE_DATA_INVALID, "画像数据序列化失败", e);
        }
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 算法不可用", e);
        }
    }

    public record ConfirmedProfile(ResumeProfile entity, UserProfileData data, String profileHash) {
    }
}
