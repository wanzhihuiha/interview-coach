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
 * 被解析状态服务、持久化服务和查询服务调用，维护用户简历的事实草稿与正式画像。
 * 本组件负责规范化、确认校验、JSON、经验等级、岗位分类和事实 hash；
 * 调用方负责把这些写入纳入简历状态事务，辅助分析和文件生命周期不在本组件内处理。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ResumeProfileSupport {

    /**
     * 当前事实画像与草稿写入数据库时使用的 Schema 版本。
     * 仓库未提供版本 2 的升级或兼容记录，精确取值依据缺失；调整会改变新写数据的版本标记，但当前读取路径未按版本分流。
     */
    public static final int PROFILE_SCHEMA_VERSION = 2;

    /** 查询和保存用户正式事实画像的仓储。 */
    private final ResumeProfileRepository resumeProfileRepository;
    /** 查询、保存和删除当前解析草稿的仓储。 */
    private final ResumeProfileDraftRepository draftRepository;
    /** 统一集合、技能别名和经验等级派生的规范化组件。 */
    private final ResumeProfileNormalizer normalizer;
    /** 在草稿转为正式画像前检查最小可确认事实的校验器。 */
    private final ResumeProfileValidator validator;
    /** 序列化和解析事实画像 JSON 的项目 ObjectMapper。 */
    private final ObjectMapper objectMapper;

    /**
     * 保存模型提取出的当前代次草稿。草稿允许内容为空，最终完整性在确认时校验。
     */
    public ResumeProfileDraft saveDraft(Long resumeId, Long userId, Long generation, UserProfileData data) {
        // 草稿允许内容不完整，但先统一空集合、技能别名和明示等级。
        UserProfileData normalized = normalizeDraft(data);
        // 每份用户简历只维护一条当前草稿；不存在时创建并写入归属。
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
        // 草稿代次与解析任务一致，确认接口将据此拒绝旧页面覆盖新解析结果。
        return draftRepository.save(draft);
    }

    /**
     * 保存用户正在编辑的草稿；generation 不一致时拒绝覆盖较新的解析结果。
     */
    public ResumeProfileDraft updateDraft(Long resumeId, Long userId, Long generation, UserProfileData data) {
        // 按用户归属读取当前草稿，其他用户或缺失资源使用同一业务错误。
        ResumeProfileDraft draft = draftRepository.findByResumeIdAndUserId(resumeId, userId)
                .orElseThrow(() -> new BusinessException(
                        ResumeErrorCode.PROFILE_DRAFT_NOT_FOUND, "待确认画像不存在"));
        if (!generation.equals(draft.getParseGeneration())) {
            throw new BusinessException(ResumeErrorCode.PROFILE_DRAFT_STALE, "画像草稿已过期，请刷新后重试");
        }
        UserProfileData normalized = normalizeDraft(data);
        // 用户编辑仍只保存为草稿，不执行正式画像的最小事实校验。
        draft.setProfileData(toJson(normalized));
        draft.setExperienceLevel(normalizer.inferExperienceLevel(normalized));
        return draftRepository.save(draft);
    }

    /**
     * 将当前 generation 的草稿确认成正式画像，并删除已消费草稿。
     */
    public ConfirmedProfile confirmDraft(
            Long resumeId, Long userId, Long generation, UserProfileData confirmedData) {
        // 正式确认必须消费当前用户、当前解析代次的草稿，过期草稿不能被客户端确认。
        ResumeProfileDraft draft = draftRepository.findByResumeIdAndUserId(resumeId, userId)
                .orElseThrow(() -> new BusinessException(
                        ResumeErrorCode.PROFILE_DRAFT_NOT_FOUND, "待确认画像不存在"));
        if (!generation.equals(draft.getParseGeneration())) {
            throw new BusinessException(ResumeErrorCode.PROFILE_DRAFT_STALE, "画像草稿已过期，请刷新后重试");
        }

        // 用户提交事实先规范化并执行正式画像最小内容校验，失败时不改草稿或正式画像。
        UserProfileData normalized = normalizeAndValidate(confirmedData);
        // 使用规范化 JSON 计算稳定事实 hash，辅助分析据此判断旧结果是否仍匹配。
        String json = toJson(normalized);
        String profileHash = sha256(json);
        // 每份简历维护一条用户正式画像，不存在时创建并写入资源归属。
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
        // 保存正式画像后删除已消费草稿；调用方事务使两步与简历 CONFIRMED 状态共同提交。
        ResumeProfile saved = resumeProfileRepository.save(profile);
        draftRepository.delete(draft);
        log.info("[ResumeProfile] 正式画像已确认: resumeId={}, profileId={}", resumeId, saved.getId());
        return new ConfirmedProfile(saved, normalized, profileHash);
    }

    /** 按用户归属读取并解析正式画像；不存在返回空，坏 JSON 抛稳定业务异常。 */
    public Optional<UserProfileData> loadConfirmed(Long resumeId, Long userId) {
        // 仓储结果存在时才解析持久化 JSON；空结果不创建默认画像。
        return resumeProfileRepository.findByResumeIdAndUserId(resumeId, userId)
                .map(profile -> fromJson(resumeId, profile.getProfileData()));
    }

    /** 按用户归属读取并解析当前草稿；不存在返回空，坏 JSON 抛稳定业务异常。 */
    public Optional<UserProfileData> loadDraft(Long resumeId, Long userId) {
        // 草稿查询与 JSON 转换沿用同一用户归属，空结果原样返回给调用方选择回退路径。
        return draftRepository.findByResumeIdAndUserId(resumeId, userId)
                .map(draft -> fromJson(resumeId, draft.getProfileData()));
    }

    /** 将数据库画像 JSON 还原为事实对象；空值或坏 JSON 阻止调用链继续使用损坏画像。 */
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

    /**
     * 根据技能标签中的产品或设计关键词派生岗位分类；无事实、无命中或其他标签均兜底为 TECH。
     * 关键词集合及 TECH 兜底的产品依据缺失；调整会改变简历列表和后续岗位分类展示。
     */
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

    /** 对正式确认数据先规范化再校验，确保校验观察的是最终持久化形态。 */
    private UserProfileData normalizeAndValidate(UserProfileData data) {
        UserProfileData normalized = normalizeDraft(data);
        validator.validate(normalized);
        return normalized;
    }

    /** 拒绝空画像并应用统一规范化；不执行正式画像最小事实校验。 */
    private UserProfileData normalizeDraft(UserProfileData data) {
        if (data == null) {
            throw new BusinessException(ResumeErrorCode.PROFILE_DATA_INVALID, "画像数据无效");
        }
        return normalizer.normalize(data);
    }

    /** 将规范化事实序列化为数据库 JSON；失败时映射为画像数据业务错误。 */
    private String toJson(UserProfileData data) {
        try {
            return objectMapper.writeValueAsString(data);
        } catch (JsonProcessingException e) {
            throw new BusinessException(ResumeErrorCode.PROFILE_DATA_INVALID, "画像数据序列化失败", e);
        }
    }

    /** 对 UTF-8 规范化 JSON 计算 SHA-256 十六进制摘要，供事实版本隔离而非资源授权使用。 */
    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 算法不可用", e);
        }
    }

    /**
     * 草稿成功转为正式画像后的事务内结果，供持久化服务同步简历状态和辅助分析资格。
     *
     * @param entity 已保存、属于当前用户和简历的正式画像实体
     * @param data 实际写入实体 JSON 的规范化事实对象
     * @param profileHash 规范化事实 JSON 的 SHA-256 摘要，用于隔离辅助分析版本
     */
    public record ConfirmedProfile(ResumeProfile entity, UserProfileData data, String profileHash) {
    }
}
