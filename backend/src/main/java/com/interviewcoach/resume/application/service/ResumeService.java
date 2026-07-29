package com.interviewcoach.resume.application.service;

import static com.interviewcoach.resume.application.service.ResumeErrorCode.*;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.interviewcoach.common.domain.JobCategoryType;
import com.interviewcoach.common.exception.BusinessException;
import com.interviewcoach.resume.domain.agent.ResumeAnalysisAgent;
import com.interviewcoach.resume.domain.entity.ExperienceLevel;
import com.interviewcoach.resume.domain.entity.Resume;
import com.interviewcoach.resume.domain.entity.ResumeParseStatus;
import com.interviewcoach.resume.domain.entity.ResumeProfile;
import com.interviewcoach.resume.domain.model.UserProfileData;
import com.interviewcoach.resume.domain.repository.ResumeProfileRepository;
import com.interviewcoach.resume.domain.repository.ResumeRepository;
import com.interviewcoach.resume.infrastructure.parser.ResumeTextExtractor;
import com.interviewcoach.resume.infrastructure.storage.FileStorageService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * 简历应用服务，处理简历上传、解析、确认与管理。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ResumeService {

    private static final String REDIS_CACHE_PREFIX = "resume:parse:";
    private static final long CACHE_TTL_SECONDS = 7 * 24 * 60 * 60;

    private final ResumeRepository resumeRepository;
    private final ResumeProfileRepository resumeProfileRepository;
    private final FileStorageService fileStorageService;
    private final ResumeTextExtractor textExtractor;
    private final ResumeAnalysisAgent resumeAnalysisAgent;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    @Value("${resume.upload.max-size:10485760}")
    private long maxFileSize;

    /**
     * 上传简历文件。
     */
    @Transactional
    public com.interviewcoach.resume.application.dto.ResumeUploadResponse uploadResume(Long userId, MultipartFile file, String fileType) {
        validateFile(file, fileType);

        String filePath = fileStorageService.store(userId, file);

        Resume resume = new Resume();
        resume.setUserId(userId);
        resume.setResumeName(file.getOriginalFilename());
        resume.setFilePath(filePath);
        resume.setFileType(fileType.toUpperCase());
        resume.setFileSize(file.getSize());
        resume.setParseStatus(ResumeParseStatus.PENDING);
        resumeRepository.save(resume);

        // 异步触发解析
        parseResumeAsync(resume.getId(), userId);

        return new com.interviewcoach.resume.application.dto.ResumeUploadResponse(
                resume.getId(), resume.getResumeName(), resume.getParseStatus().name(),
                resume.getParseStatus().getDisplayName(), 0);
    }

    /**
     * 异步解析简历，从已存储的文件中读取文本。
     */
    @Async
    public void parseResumeAsync(Long resumeId, Long userId) {
        Resume resume = resumeRepository.findById(resumeId).orElse(null);
        if (resume == null) {
            log.warn("[ResumeService] 异步解析时简历不存在: resumeId={}", resumeId);
            return;
        }
        resume.setParseStatus(ResumeParseStatus.PARSING);
        resumeRepository.save(resume);

        String resumeText;
        try {
            resumeText = textExtractor.extractFromFile(resume.getFilePath(), resume.getFileType());
        } catch (Exception e) {
            log.error("[ResumeService] 简历文本提取失败: resumeId={}", resumeId, e);
            resume.setParseStatus(ResumeParseStatus.PARSE_FAILED);
            resumeRepository.save(resume);
            return;
        }

        if (resumeText.isBlank()) {
            log.warn("[ResumeService] 简历解析内容为空: resumeId={}", resumeId);
            resume.setParseStatus(ResumeParseStatus.PARSE_FAILED);
            resumeRepository.save(resume);
            return;
        }

        // 缓存检查
        String md5 = md5(resumeText);
        String cacheKey = REDIS_CACHE_PREFIX + md5;
        String cached = redisTemplate.opsForValue().get(cacheKey);
        UserProfileData profileData;
        if (cached != null) {
            log.info("[ResumeService] 简历解析缓存命中: resumeId={}", resumeId);
            try {
                profileData = objectMapper.readValue(cached, UserProfileData.class);
            } catch (JsonProcessingException e) {
                log.warn("[ResumeService] 缓存解析失败，重新调用 LLM: resumeId={}", resumeId, e);
                profileData = resumeAnalysisAgent.analyze(resumeText);
            }
        } else {
            profileData = resumeAnalysisAgent.analyze(resumeText);
            try {
                redisTemplate.opsForValue().set(cacheKey, objectMapper.writeValueAsString(profileData),
                        java.time.Duration.ofSeconds(CACHE_TTL_SECONDS));
            } catch (JsonProcessingException e) {
                log.warn("[ResumeService] 缓存写入失败: resumeId={}", resumeId, e);
            }
        }

        saveProfile(resumeId, userId, profileData);
        resume.setParseStatus(ResumeParseStatus.PENDING_CONFIRM);
        resume.setJobCategory(inferJobCategory(profileData));
        resumeRepository.save(resume);
    }

    /**
     * 查询简历列表。
     */
    @Transactional(readOnly = true)
    public com.interviewcoach.resume.application.dto.ResumeListResponse listResumes(Long userId, int page, int size) {
        Pageable pageable = PageRequest.of(page, size);
        Page<Resume> resumePage = resumeRepository.findByUserId(userId, pageable);

        com.interviewcoach.resume.application.dto.ResumeListResponse response = new com.interviewcoach.resume.application.dto.ResumeListResponse();
        response.setContent(resumePage.getContent().stream().map(this::toListItem).toList());
        response.setTotalElements(resumePage.getTotalElements());
        response.setTotalPages(resumePage.getTotalPages());
        response.setCurrentPage(resumePage.getNumber());
        return response;
    }

    /**
     * 查询简历详情。
     */
    @Transactional(readOnly = true)
    public com.interviewcoach.resume.application.dto.ResumeDetailResponse getResumeDetail(Long userId, Long resumeId) {
        Resume resume = findResumeByIdAndUserId(resumeId, userId);
        com.interviewcoach.resume.application.dto.ResumeDetailResponse response = new com.interviewcoach.resume.application.dto.ResumeDetailResponse();
        response.setResumeId(resume.getId());
        response.setFileName(resume.getResumeName());
        response.setFileType(resume.getFileType());
        response.setFileSize(resume.getFileSize());
        response.setStatus(resume.getParseStatus().name());
        response.setStatusLabel(resume.getParseStatus().getDisplayName());
        response.setJobCategory(resume.getJobCategory());
        response.setJobCategoryLabel(JobCategoryType.displayNameOf(resume.getJobCategory()));
        response.setCreatedAt(resume.getCreatedAt());
        response.setConfirmedAt(resume.getParseStatus() == ResumeParseStatus.CONFIRMED ? resume.getUpdatedAt() : null);

        resumeProfileRepository.findByResumeId(resumeId)
                .ifPresent(profile -> response.setParsedData(parseProfileJson(profile.getProfileData())));
        return response;
    }

    /**
     * 查询简历画像。
     */
    @Transactional(readOnly = true)
    public com.interviewcoach.resume.application.dto.ResumeProfileResponse getResumeProfile(Long userId, Long resumeId) {
        Resume resume = findResumeByIdAndUserId(resumeId, userId);
        ResumeProfile profile = resumeProfileRepository.findByResumeId(resumeId)
                .orElseThrow(() -> new BusinessException(RESUME_NOT_FOUND, "简历画像不存在"));

        com.interviewcoach.resume.application.dto.ResumeProfileResponse response = new com.interviewcoach.resume.application.dto.ResumeProfileResponse();
        response.setProfileId(profile.getId());
        response.setResumeId(profile.getResumeId());
        response.setProfile(parseProfileJson(profile.getProfileData()));
        response.setExperienceLevel(profile.getExperienceLevel());
        response.setExperienceLevelLabel(ExperienceLevel.displayNameOf(profile.getExperienceLevel()));
        response.setStatus(resume.getParseStatus().name());
        response.setStatusLabel(resume.getParseStatus().getDisplayName());
        return response;
    }

    /**
     * 用户确认简历解析结果。
     */
    @Transactional
    public void confirmResume(Long userId, Long resumeId, UserProfileData profileData) {
        Resume resume = findResumeByIdAndUserId(resumeId, userId);
        if (resume.getParseStatus() != ResumeParseStatus.PENDING_CONFIRM) {
            throw new BusinessException(RESUME_STATUS_INVALID, "当前状态不允许确认");
        }
        if (profileData == null) {
            throw new BusinessException(PROFILE_DATA_INVALID, "画像数据无效");
        }

        saveProfile(resumeId, userId, profileData);
        resume.setParseStatus(ResumeParseStatus.CONFIRMED);
        resume.setJobCategory(inferJobCategory(profileData));
        resumeRepository.save(resume);
    }

    /**
     * 重新解析简历。
     */
    @Transactional
    public com.interviewcoach.resume.application.dto.ResumeUploadResponse reparseResume(Long userId, Long resumeId) {
        Resume resume = findResumeByIdAndUserId(resumeId, userId);
        if (resume.isLocked()) {
            throw new BusinessException(RESUME_LOCKED, "简历已锁定，不可重新解析");
        }
        if (resume.getParseStatus() == ResumeParseStatus.PARSING) {
            throw new BusinessException(RESUME_PARSING_IN_PROGRESS, "简历正在解析中，请稍后再试");
        }

        // 清除缓存，强制重新解析
        resume.setParseStatus(ResumeParseStatus.PENDING);
        resumeRepository.save(resume);
        parseResumeAsync(resume.getId(), userId);

        return new com.interviewcoach.resume.application.dto.ResumeUploadResponse(
                resume.getId(), resume.getResumeName(), resume.getParseStatus().name(),
                resume.getParseStatus().getDisplayName(), 0);
    }

    /**
     * 删除简历。
     */
    @Transactional
    public void deleteResume(Long userId, Long resumeId) {
        Resume resume = findResumeByIdAndUserId(resumeId, userId);
        if (resume.isLocked()) {
            throw new BusinessException(RESUME_LOCKED_FOR_DELETE, "简历已锁定，不可删除");
        }
        fileStorageService.delete(resume.getFilePath());
        resumeProfileRepository.findByResumeId(resumeId).ifPresent(resumeProfileRepository::delete);
        resumeRepository.delete(resume);
    }

    private void validateFile(MultipartFile file, String fileType) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(FILE_READ_FAILED, "文件为空");
        }
        if (file.getSize() > maxFileSize) {
            throw new BusinessException(FILE_SIZE_EXCEEDED, "文件大小超过10MB限制");
        }
        String type = fileType == null ? "" : fileType.toUpperCase();
        if (!type.equals("PDF") && !type.equals("TXT")) {
            throw new BusinessException(FILE_TYPE_NOT_SUPPORTED, "仅支持 PDF 和 TXT 格式");
        }
    }

    private Resume findResumeByIdAndUserId(Long resumeId, Long userId) {
        return resumeRepository.findByIdAndUserId(resumeId, userId)
                .orElseThrow(() -> new BusinessException(RESUME_NOT_FOUND, "简历不存在"));
    }

    private void saveProfile(Long resumeId, Long userId, UserProfileData data) {
        String json;
        try {
            json = objectMapper.writeValueAsString(data);
        } catch (JsonProcessingException e) {
            throw new BusinessException(PROFILE_DATA_INVALID, "画像数据序列化失败", e);
        }

        ResumeProfile profile = resumeProfileRepository.findByResumeId(resumeId)
                .orElseGet(() -> {
                    ResumeProfile p = new ResumeProfile();
                    p.setResumeId(resumeId);
                    p.setUserId(userId);
                    return p;
                });
        profile.setProfileData(json);
        profile.setExperienceLevel(resumeAnalysisAgent.inferExperienceLevel(data));
        resumeProfileRepository.save(profile);
    }

    private UserProfileData parseProfileJson(String json) {
        if (json == null || json.isBlank()) {
            return UserProfileData.empty();
        }
        String normalized = json.trim();
        // H2 JSON 列可能把对象存成了 JSON 字符串字面量，需要二次解析
        if (normalized.startsWith("\"") && normalized.endsWith("\"")) {
            try {
                normalized = objectMapper.readValue(normalized, String.class);
            } catch (JsonProcessingException e) {
                log.warn("[ResumeService] profile_data 字符串字面量解码失败，尝试直接解析", e);
            }
        }
        try {
            return objectMapper.readValue(normalized, UserProfileData.class);
        } catch (JsonProcessingException e) {
            log.error("[ResumeService] 画像 JSON 解析失败", e);
            return UserProfileData.empty();
        }
    }

    private com.interviewcoach.resume.application.dto.ResumeListItemResponse toListItem(Resume resume) {
        com.interviewcoach.resume.application.dto.ResumeListItemResponse item = new com.interviewcoach.resume.application.dto.ResumeListItemResponse();
        item.setResumeId(resume.getId());
        item.setFileName(resume.getResumeName());
        item.setStatus(resume.getParseStatus().name());
        item.setStatusLabel(resume.getParseStatus().getDisplayName());
        item.setJobCategory(resume.getJobCategory());
        item.setJobCategoryLabel(JobCategoryType.displayNameOf(resume.getJobCategory()));
        item.setCreatedAt(resume.getCreatedAt());
        item.setUpdatedAt(resume.getUpdatedAt());
        return item;
    }

    private String inferJobCategory(UserProfileData data) {
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

    private String md5(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("MD5 算法不可用", e);
        }
    }
}
