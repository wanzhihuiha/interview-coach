package com.interviewcoach.resume.application.service;

import static com.interviewcoach.resume.application.service.ResumeErrorCode.*;

import com.interviewcoach.common.domain.JobCategoryType;
import com.interviewcoach.common.exception.BusinessException;
import com.interviewcoach.resume.application.dto.ResumeDetailResponse;
import com.interviewcoach.resume.application.dto.ResumeListItemResponse;
import com.interviewcoach.resume.application.dto.ResumeListResponse;
import com.interviewcoach.resume.application.dto.ResumeParseStatusResponse;
import com.interviewcoach.resume.application.dto.ResumeProfileAnalysisRetryRequest;
import com.interviewcoach.resume.application.dto.ResumeProfileResponse;
import com.interviewcoach.resume.application.dto.ResumeUploadResponse;
import com.interviewcoach.resume.application.service.ResumePersistenceService.ConfirmedResume;
import com.interviewcoach.resume.application.service.ResumeProfileAnalysisStateService.AnalysisView;
import com.interviewcoach.resume.domain.entity.ExperienceLevel;
import com.interviewcoach.resume.domain.entity.Resume;
import com.interviewcoach.resume.domain.entity.ResumeParseStatus;
import com.interviewcoach.resume.domain.entity.ResumeProfile;
import com.interviewcoach.resume.domain.entity.ResumeProfileDraft;
import com.interviewcoach.resume.domain.model.UserProfileData;
import com.interviewcoach.resume.domain.repository.ResumeProfileDraftRepository;
import com.interviewcoach.resume.domain.repository.ResumeProfileRepository;
import com.interviewcoach.resume.domain.repository.ResumeRepository;
import com.interviewcoach.resume.infrastructure.storage.FileStorageService;
import com.interviewcoach.resume.infrastructure.redis.ResumeUserMutationLock;
import com.interviewcoach.user.application.service.ConsentService;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * 简历应用服务，编排上传、事实草稿、用户确认、重新解析和辅助分析。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ResumeService {

    private final ResumeRepository resumeRepository;
    private final ResumeProfileRepository profileRepository;
    private final ResumeProfileDraftRepository draftRepository;
    private final FileStorageService fileStorageService;
    private final ResumePersistenceService persistenceService;
    private final ResumeUploadService uploadService;
    private final ResumeAiTaskSubmissionService taskSubmissionService;
    private final ResumeUserMutationLock mutationLock;
    private final ResumeProfileSupport profileSupport;
    private final ResumeProfileAnalysisStateService analysisStateService;
    private final ConsentService consentService;

    /**
     * 上传的文件、数据库、Redis 许可与日额度由独立补偿编排处理。
     */
    public ResumeUploadResponse uploadResume(Long userId, MultipartFile file, String fileType) {
        return uploadService.upload(userId, file, fileType);
    }

    @Transactional(readOnly = true)
    public ResumeListResponse listResumes(Long userId, int page, int size) {
        Pageable pageable = PageRequest.of(page, size);
        Page<Resume> resumePage = resumeRepository.findByUserId(userId, pageable);
        List<Long> resumeIds = resumePage.getContent().stream().map(Resume::getId).toList();
        Set<Long> confirmedIds = resumeIds.isEmpty() ? Set.of()
                : new HashSet<>(profileRepository.findResumeIdsByUserIdAndResumeIdIn(userId, resumeIds));

        ResumeListResponse response = new ResumeListResponse();
        response.setContent(resumePage.getContent().stream()
                .map(resume -> toListItem(resume, confirmedIds.contains(resume.getId())))
                .toList());
        response.setTotalElements(resumePage.getTotalElements());
        response.setTotalPages(resumePage.getTotalPages());
        response.setCurrentPage(resumePage.getNumber());
        return response;
    }

    @Transactional(readOnly = true)
    public ResumeDetailResponse getResumeDetail(Long userId, Long resumeId) {
        Resume resume = findResumeByIdAndUserId(resumeId, userId);
        Optional<ResumeProfile> confirmed = profileRepository.findByResumeIdAndUserId(resumeId, userId);
        Optional<ResumeProfileDraft> draft = currentDraft(resume, userId);

        ResumeDetailResponse response = new ResumeDetailResponse();
        response.setResumeId(resume.getId());
        response.setFileName(resume.getResumeName());
        response.setFileType(resume.getFileType());
        response.setFileSize(resume.getFileSize());
        response.setStatus(resume.getParseStatus().name());
        response.setStatusLabel(resume.getParseStatus().getDisplayName());
        response.setJobCategory(resume.getJobCategory());
        response.setJobCategoryLabel(JobCategoryType.displayNameOf(resume.getJobCategory()));
        response.setParsedData(draft.map(item -> profileSupport.fromJson(resumeId, item.getProfileData()))
                .orElseGet(() -> confirmed.map(item -> profileSupport.fromJson(resumeId, item.getProfileData()))
                        .orElse(null)));
        response.setHasConfirmedProfile(confirmed.isPresent());
        response.setParseErrorCode(resume.getParseErrorCode());
        response.setParseErrorMessage(resume.getParseErrorMessage());
        response.setCreatedAt(resume.getCreatedAt());
        response.setConfirmedAt(confirmed.map(ResumeProfile::getConfirmedAt).orElse(null));
        return response;
    }

    @Transactional(readOnly = true)
    public ResumeProfileResponse getResumeProfile(Long userId, Long resumeId) {
        Resume resume = findResumeByIdAndUserId(resumeId, userId);
        Optional<ResumeProfile> confirmed = profileRepository.findByResumeIdAndUserId(resumeId, userId);
        Optional<ResumeProfileDraft> draft = currentDraft(resume, userId);
        Optional<AnalysisView> analysis = analysisStateService.loadCurrent(resumeId, userId);
        if (confirmed.isEmpty() && draft.isEmpty()) {
            throw new BusinessException(RESUME_NOT_FOUND, "简历画像不存在");
        }

        UserProfileData confirmedData = confirmed
                .map(item -> profileSupport.fromJson(resumeId, item.getProfileData())).orElse(null);
        UserProfileData draftData = draft
                .map(item -> profileSupport.fromJson(resumeId, item.getProfileData())).orElse(null);
        ResumeProfileResponse response = new ResumeProfileResponse();
        response.setProfileId(confirmed.map(ResumeProfile::getId).orElse(null));
        response.setResumeId(resumeId);
        response.setProfile(draftData != null ? draftData : confirmedData);
        response.setConfirmedProfile(confirmedData);
        response.setDraftProfile(draftData);
        response.setAnalysis(analysis.map(AnalysisView::data).orElse(null));
        response.setHasConfirmedProfile(confirmed.isPresent());
        response.setParseGeneration(resume.getParseGeneration());
        String experienceLevel = draft.map(ResumeProfileDraft::getExperienceLevel)
                .orElseGet(() -> confirmed.map(ResumeProfile::getExperienceLevel).orElse(null));
        response.setExperienceLevel(experienceLevel);
        response.setExperienceLevelLabel(ExperienceLevel.displayNameOf(experienceLevel));
        response.setStatus(resume.getParseStatus().name());
        response.setStatusLabel(resume.getParseStatus().getDisplayName());
        response.setAnalysisUsableForInterview(false);
        response.setAnalysisRefineAllowed(false);
        analysis.ifPresent(view -> {
            response.setAnalysisStatus(view.effectiveStatus().name());
            response.setAnalysisErrorMessage(view.effectiveErrorMessage());
            response.setAnalysisUsableForInterview(view.usableForInterview());
            response.setAnalysisRefineAllowed(view.refineAllowed());
            response.setAnalysisTaskGeneration(view.entity().getTaskGeneration());
            response.setAnalysisMode(view.entity().getTaskMode() == null
                    ? null : view.entity().getTaskMode().name());
        });
        return response;
    }

    @Transactional(readOnly = true)
    public ResumeParseStatusResponse getParseStatus(Long userId, Long resumeId) {
        Resume resume = findResumeByIdAndUserId(resumeId, userId);
        boolean hasConfirmed = profileRepository.findByResumeIdAndUserId(resumeId, userId).isPresent();
        String analysisStatus = analysisStateService.loadCurrent(resumeId, userId)
                .map(view -> view.effectiveStatus().name()).orElse(null);
        return new ResumeParseStatusResponse(
                resume.getId(),
                resume.getParseStatus().name(),
                resume.getParseStatus().getDisplayName(),
                parseProgress(resume.getParseStatus()),
                resume.getParseGeneration(),
                hasConfirmed,
                resume.getParseErrorCode(),
                resume.getParseErrorMessage(),
                analysisStatus,
                resume.getUpdatedAt());
    }

    /**
     * 保存当前代次的用户编辑草稿；新接口必须携带 generation，防止旧页面覆盖新结果。
     */
    @Transactional
    public void updateProfileDraft(
            Long userId, Long resumeId, Long requestedGeneration, UserProfileData profileData) {
        Resume resume = findResumeByIdAndUserIdForUpdate(resumeId, userId);
        if (resume.getParseStatus() != ResumeParseStatus.PENDING_CONFIRM) {
            throw new BusinessException(RESUME_STATUS_INVALID, "当前状态不允许修改画像草稿");
        }
        Long generation = requireCurrentGeneration(requestedGeneration, resume);
        profileSupport.updateDraft(resumeId, userId, generation, profileData);
    }

    /**
     * 先独立提交正式事实，再尝试可选 INITIAL；后一步失败不会回滚或覆盖已确认事实。
     */
    public void confirmResume(
            Long userId, Long resumeId, Long requestedGeneration, UserProfileData profileData) {
        consentService.requireAiProcessingConsent(userId);
        ConfirmedResume confirmed = persistenceService.confirmProfile(
                userId, resumeId, requestedGeneration, profileData);
        taskSubmissionService.submitOptionalInitial(userId, confirmed);
    }

    /**
     * 保留当前正式画像并创建新解析代次；提交后才把任务交给进程内线程池。
     */
    public ResumeUploadResponse reparseResume(Long userId, Long resumeId) {
        consentService.requireAiProcessingConsent(userId);
        Resume resume = taskSubmissionService.submitManualReparse(userId, resumeId);
        return toUploadResponse(resume);
    }

    /**
     * 只重试当前正式画像的辅助分析，不重新解析原简历文件。
     */
    public void retryProfileAnalysis(
            Long userId, Long resumeId, ResumeProfileAnalysisRetryRequest request) {
        consentService.requireAiProcessingConsent(userId);
        taskSubmissionService.submitManualAnalysis(userId, resumeId, request);
    }

    public void deleteResume(Long userId, Long resumeId) {
        String filePath = mutationLock.execute(
                userId, () -> persistenceService.deleteOwned(userId, resumeId));
        fileStorageService.delete(filePath);
    }

    private Optional<ResumeProfileDraft> currentDraft(Resume resume, Long userId) {
        return draftRepository.findByResumeIdAndUserId(resume.getId(), userId)
                .filter(draft -> draft.getParseGeneration().equals(resume.getParseGeneration()));
    }

    private Long requireCurrentGeneration(Long requestedGeneration, Resume resume) {
        if (requestedGeneration == null || !requestedGeneration.equals(resume.getParseGeneration())) {
            throw new BusinessException(PROFILE_DRAFT_STALE, "画像草稿已过期，请刷新后重试");
        }
        return requestedGeneration;
    }

    private Resume findResumeByIdAndUserId(Long resumeId, Long userId) {
        return resumeRepository.findByIdAndUserId(resumeId, userId)
                .orElseThrow(() -> new BusinessException(RESUME_NOT_FOUND, "简历不存在"));
    }

    private Resume findResumeByIdAndUserIdForUpdate(Long resumeId, Long userId) {
        return resumeRepository.findByIdAndUserIdForUpdate(resumeId, userId)
                .orElseThrow(() -> new BusinessException(RESUME_NOT_FOUND, "简历不存在"));
    }

    private ResumeListItemResponse toListItem(Resume resume, boolean hasConfirmedProfile) {
        ResumeListItemResponse item = new ResumeListItemResponse();
        item.setResumeId(resume.getId());
        item.setFileName(resume.getResumeName());
        item.setStatus(resume.getParseStatus().name());
        item.setStatusLabel(resume.getParseStatus().getDisplayName());
        item.setJobCategory(resume.getJobCategory());
        item.setJobCategoryLabel(JobCategoryType.displayNameOf(resume.getJobCategory()));
        item.setHasConfirmedProfile(hasConfirmedProfile);
        item.setParseErrorMessage(resume.getParseErrorMessage());
        item.setCreatedAt(resume.getCreatedAt());
        item.setUpdatedAt(resume.getUpdatedAt());
        return item;
    }

    private ResumeUploadResponse toUploadResponse(Resume resume) {
        return new ResumeUploadResponse(
                resume.getId(), resume.getResumeName(), resume.getParseStatus().name(),
                resume.getParseStatus().getDisplayName(), 10);
    }

    private int parseProgress(ResumeParseStatus status) {
        return switch (status) {
            case PENDING -> 10;
            case PARSING -> 50;
            case PENDING_CONFIRM, CONFIRMED -> 100;
            case PARSE_FAILED -> 0;
        };
    }
}
