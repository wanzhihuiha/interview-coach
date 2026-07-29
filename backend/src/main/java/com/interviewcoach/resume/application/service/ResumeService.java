package com.interviewcoach.resume.application.service;

import static com.interviewcoach.resume.application.service.ResumeErrorCode.*;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.interviewcoach.common.domain.JobCategoryType;
import com.interviewcoach.common.exception.BusinessException;
import com.interviewcoach.resume.application.dto.ResumeParseStatusResponse;
import com.interviewcoach.resume.application.event.ResumeParseRequestedEvent;
import com.interviewcoach.resume.domain.entity.ExperienceLevel;
import com.interviewcoach.resume.domain.entity.Resume;
import com.interviewcoach.resume.domain.entity.ResumeParseStatus;
import com.interviewcoach.resume.domain.entity.ResumeProfile;
import com.interviewcoach.resume.domain.model.UserProfileData;
import com.interviewcoach.resume.domain.repository.ResumeProfileRepository;
import com.interviewcoach.resume.domain.repository.ResumeRepository;
import com.interviewcoach.resume.infrastructure.storage.FileStorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * 简历应用服务，负责用户侧简历管理与解析任务编排。
 *
 * <p>上传和重新解析只在当前事务中登记 {@link ResumeParseRequestedEvent}；
 * 真正的文件提取、缓存访问和 LLM 分析由事务提交后的后台任务执行。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ResumeService {

    private final ResumeRepository resumeRepository;
    private final ResumeProfileRepository resumeProfileRepository;
    private final FileStorageService fileStorageService;
    private final ResumeProfileSupport resumeProfileSupport;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher eventPublisher;

    @Value("${resume.upload.max-size:10485760}")
    private long maxFileSize;

    /**
     * 保存上传文件和 {@code PENDING} 简历记录，并登记后台解析事件。
     * 事件监听器仅在当前事务成功提交后入队，避免后台任务读取到未提交记录。
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

        eventPublisher.publishEvent(new ResumeParseRequestedEvent(resume.getId(), userId, false));
        log.info("[ResumeParse] 上传解析请求已登记，等待事务提交后调度: resumeId={}, userId={}, fileType={}, fileSize={}",
                resume.getId(), userId, resume.getFileType(), resume.getFileSize());

        return new com.interviewcoach.resume.application.dto.ResumeUploadResponse(
                resume.getId(), resume.getResumeName(), resume.getParseStatus().name(),
                resume.getParseStatus().getDisplayName(), 10);
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
                .ifPresent(profile -> response.setParsedData(parseProfileJson(resumeId, profile.getProfileData())));
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
        response.setProfile(parseProfileJson(resumeId, profile.getProfileData()));
        response.setExperienceLevel(profile.getExperienceLevel());
        response.setExperienceLevelLabel(ExperienceLevel.displayNameOf(profile.getExperienceLevel()));
        response.setStatus(resume.getParseStatus().name());
        response.setStatusLabel(resume.getParseStatus().getDisplayName());
        return response;
    }

    /**
     * 查询简历后台解析任务的当前状态。
     * 返回的进度是状态对应的展示值，不代表文件或 LLM 的实时完成百分比。
     */
    @Transactional(readOnly = true)
    public ResumeParseStatusResponse getParseStatus(Long userId, Long resumeId) {
        Resume resume = findResumeByIdAndUserId(resumeId, userId);
        int progress = parseProgress(resume.getParseStatus());
        log.debug("[ResumeParse] 查询解析状态: resumeId={}, status={}, progress={}",
                resumeId, resume.getParseStatus(), progress);
        return new ResumeParseStatusResponse(
                resume.getId(),
                resume.getParseStatus().name(),
                resume.getParseStatus().getDisplayName(),
                progress,
                resume.getUpdatedAt());
    }

    /**
     * 用户确认并覆盖当前画像，将简历从 {@code PENDING_CONFIRM} 推进为 {@code CONFIRMED}。
     * 画像写入、岗位类别更新和状态流转处于同一事务中。
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

        resumeProfileSupport.saveProfile(resumeId, userId, profileData);
        resume.setParseStatus(ResumeParseStatus.CONFIRMED);
        resume.setJobCategory(resumeProfileSupport.inferJobCategory(profileData));
        resumeRepository.save(resume);
        log.info("[ResumeParse] 用户确认结果已写入当前事务: resumeId={}, userId={}, from={}, to={}",
                resumeId, userId, ResumeParseStatus.PENDING_CONFIRM, ResumeParseStatus.CONFIRMED);
    }

    /**
     * 将可重新处理的简历重置为 {@code PENDING} 并登记强制刷新事件。
     * 强制刷新会绕过已有缓存读取；事件仍只在当前事务提交后入队。
     */
    @Transactional
    public com.interviewcoach.resume.application.dto.ResumeUploadResponse reparseResume(Long userId, Long resumeId) {
        Resume resume = findResumeByIdAndUserId(resumeId, userId);
        if (resume.isLocked()) {
            throw new BusinessException(RESUME_LOCKED, "简历已锁定，不可重新解析");
        }
        if (resume.getParseStatus() == ResumeParseStatus.PENDING
                || resume.getParseStatus() == ResumeParseStatus.PARSING) {
            throw new BusinessException(RESUME_PARSING_IN_PROGRESS, "简历正在解析中，请稍后再试");
        }

        ResumeParseStatus previousStatus = resume.getParseStatus();
        resume.setParseStatus(ResumeParseStatus.PENDING);
        resumeRepository.save(resume);
        eventPublisher.publishEvent(new ResumeParseRequestedEvent(resume.getId(), userId, true));
        log.info("[ResumeParse] 重新解析请求已登记，等待事务提交后调度: resumeId={}, userId={}, from={}, to={}, forceRefresh=true",
                resumeId, userId, previousStatus, ResumeParseStatus.PENDING);

        return new com.interviewcoach.resume.application.dto.ResumeUploadResponse(
                resume.getId(), resume.getResumeName(), resume.getParseStatus().name(),
                resume.getParseStatus().getDisplayName(), 10);
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

    /**
     * 按简历 ID 和可信用户 ID 联合查询，避免仅凭客户端传入的简历 ID 跨用户访问。
     */
    private Resume findResumeByIdAndUserId(Long resumeId, Long userId) {
        return resumeRepository.findByIdAndUserId(resumeId, userId)
                .orElseThrow(() -> new BusinessException(RESUME_NOT_FOUND, "简历不存在"));
    }

    /**
     * 读取已持久化画像；兼容 H2 的 JSON 字符串字面量，空值或坏 JSON 降级为空画像。
     */
    private UserProfileData parseProfileJson(Long resumeId, String json) {
        if (json == null || json.isBlank()) {
            return UserProfileData.empty();
        }
        String normalized = json.trim();
        // H2 JSON 列可能把对象存成了 JSON 字符串字面量，需要二次解析
        if (normalized.startsWith("\"") && normalized.endsWith("\"")) {
            try {
                normalized = objectMapper.readValue(normalized, String.class);
            } catch (JsonProcessingException e) {
                log.warn("[ResumeQuery] H2 画像字符串字面量解码失败，尝试直接解析: resumeId={}, errorType={}",
                        resumeId, e.getClass().getSimpleName());
            }
        }
        try {
            return objectMapper.readValue(normalized, UserProfileData.class);
        } catch (JsonProcessingException e) {
            log.warn("[ResumeQuery] 画像 JSON 解析失败，返回空画像: resumeId={}, stage=PROFILE_DESERIALIZATION, errorType={}",
                    resumeId, e.getClass().getSimpleName());
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

    /**
     * 将持久化状态映射为前端展示进度；该值是离散提示，不是任务内部实时进度。
     */
    private int parseProgress(ResumeParseStatus status) {
        return switch (status) {
            case PENDING -> 10;
            case PARSING -> 50;
            case PENDING_CONFIRM, CONFIRMED -> 100;
            case PARSE_FAILED -> 0;
        };
    }
}
