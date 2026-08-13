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
 * 面向简历 HTTP 接口编排列表、详情、事实草稿、用户确认、重解析、辅助分析和删除用例。
 * 查询和写入都以服务端传入的当前用户标识约束资源归属；跨数据库、Redis、文件和异步事件的补偿交给专用服务处理。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ResumeService {

    /** 按用户归属查询简历列表、详情并在草稿更新时加锁的仓储。 */
    private final ResumeRepository resumeRepository;
    /** 查询用户正式画像及批量判断列表中正式画像存在性的仓储。 */
    private final ResumeProfileRepository profileRepository;
    /** 查询与当前解析代次匹配的用户草稿的仓储。 */
    private final ResumeProfileDraftRepository draftRepository;
    /** 在数据库删除提交后移除物理简历文件的存储服务。 */
    private final FileStorageService fileStorageService;
    /** 承担正式画像确认和简历删除数据库短事务的服务。 */
    private final ResumePersistenceService persistenceService;
    /** 协调上传文件、数据库、Redis 资源和事件补偿的服务。 */
    private final ResumeUploadService uploadService;
    /** 提交重解析、手动辅助分析及确认后可选 INITIAL 的服务。 */
    private final ResumeAiTaskSubmissionService taskSubmissionService;
    /** 串行同一用户上传与删除等资源变更的 Redis 锁。 */
    private final ResumeUserMutationLock mutationLock;
    /** 解析草稿或正式画像 JSON，并执行规范化与确认的组件。 */
    private final ResumeProfileSupport profileSupport;
    /** 查询保留分析结果和最新任务状态的服务。 */
    private final ResumeProfileAnalysisStateService analysisStateService;
    /** 在任何 AI 任务提交前校验用户当前处理同意的服务。 */
    private final ConsentService consentService;

    /**
     * 上传的文件、数据库、Redis 许可与日额度由独立补偿编排处理。
     */
    public ResumeUploadResponse uploadResume(Long userId, MultipartFile file, String fileType) {
        // 上传服务接管文件、数据库、Redis 许可/额度及失败补偿，返回后任务已完成事件交接。
        return uploadService.upload(userId, file, fileType);
    }

    /**
     * 按用户归属分页查询简历，并批量标记当前页哪些简历已有正式画像。
     * 页码和大小沿用调用方输入；PageRequest 的零基页码以及参数错误由现有接口/框架契约处理。
     */
    @Transactional(readOnly = true)
    public ResumeListResponse listResumes(Long userId, int page, int size) {
        // 先取得当前用户的一页简历主记录，其他用户记录不会进入结果集。
        Pageable pageable = PageRequest.of(page, size);
        Page<Resume> resumePage = resumeRepository.findByUserId(userId, pageable);
        List<Long> resumeIds = resumePage.getContent().stream().map(Resume::getId).toList();
        // 对当前页 ID 批量查询正式画像，避免逐条访问仓储；空页不发起批量查询。
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

    /**
     * 返回用户拥有的简历文件元数据、解析状态和可展示画像；当前代次草稿优先于正式画像。
     * 画像 JSON 损坏时由画像支持组件抛业务异常，不返回部分解析数据。
     */
    @Transactional(readOnly = true)
    public ResumeDetailResponse getResumeDetail(Long userId, Long resumeId) {
        // 先按当前用户归属查简历，再在相同归属下读取正式画像和当前代次草稿。
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
        // 当前代次草稿用于待确认页面；没有草稿时才回退到已确认事实，二者都没有则返回 null。
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

    /**
     * 聚合当前草稿、正式事实、保留的辅助分析和最新任务状态，供画像编辑及面试资格页面使用。
     * 返回的 profile 优先采用当前代次草稿；辅助分析只有视图判定同事实版本且可用时才开放面试或继续调整。
     */
    @Transactional(readOnly = true)
    public ResumeProfileResponse getResumeProfile(Long userId, Long resumeId) {
        // 所有主数据查询都携带当前用户归属，分析视图也不会暴露其他用户的单行结果。
        Resume resume = findResumeByIdAndUserId(resumeId, userId);
        Optional<ResumeProfile> confirmed = profileRepository.findByResumeIdAndUserId(resumeId, userId);
        Optional<ResumeProfileDraft> draft = currentDraft(resume, userId);
        Optional<AnalysisView> analysis = analysisStateService.loadCurrent(resumeId, userId);
        if (confirmed.isEmpty() && draft.isEmpty()) {
            throw new BusinessException(RESUME_NOT_FOUND, "简历画像不存在");
        }

        UserProfileData confirmedData = confirmed
                // 数据库 JSON 在返回前统一解析，损坏时阻止把不可信画像交给前端。
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
        // 有分析行时将保留结果和最新任务分开映射，并使用视图的安全降级状态与错误消息。
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

    /**
     * 返回用户简历的事实解析轮询状态、当前代次、正式画像存在性和辅助分析有效状态。
     * 进度是现有状态映射而非真实任务百分比，固定值依据缺失。
     */
    @Transactional(readOnly = true)
    public ResumeParseStatusResponse getParseStatus(Long userId, Long resumeId) {
        // 先按归属读取主记录，再分别查询正式画像和辅助分析公开状态。
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
        // 锁定当前用户简历并校验仍在待确认状态，避免与重解析或确认并发覆盖。
        Resume resume = findResumeByIdAndUserIdForUpdate(resumeId, userId);
        if (resume.getParseStatus() != ResumeParseStatus.PENDING_CONFIRM) {
            throw new BusinessException(RESUME_STATUS_INVALID, "当前状态不允许修改画像草稿");
        }
        // 客户端代次必须等于数据库当前代次，旧页面提交会被拒绝。
        Long generation = requireCurrentGeneration(requestedGeneration, resume);
        // 更新仍只写草稿；正式画像的最小事实校验留到确认动作执行。
        profileSupport.updateDraft(resumeId, userId, generation, profileData);
    }

    /**
     * 先独立提交正式事实，再尝试可选 INITIAL；后一步失败不会回滚或覆盖已确认事实。
     */
    public void confirmResume(
            Long userId, Long resumeId, Long requestedGeneration, UserProfileData profileData) {
        // 用户同意是提交正式事实后触发可选模型分析的前置条件。
        consentService.requireAiProcessingConsent(userId);
        // 先用独立数据库事务确认事实并返回 hash；失败时不尝试任何可选分析。
        ConfirmedResume confirmed = persistenceService.confirmProfile(
                userId, resumeId, requestedGeneration, profileData);
        // 正式事实事务已提交后再尝试免费 INITIAL，后续许可或调度失败不会回滚确认结果。
        taskSubmissionService.submitOptionalInitial(userId, confirmed);
    }

    /**
     * 保留当前正式画像并创建新解析代次；提交后才把任务交给进程内线程池。
     */
    public ResumeUploadResponse reparseResume(Long userId, Long resumeId) {
        // 重解析会把文件内容发送到模型，因此在占用资源前复查当前用户同意。
        consentService.requireAiProcessingConsent(userId);
        // 提交服务校验归属与状态、登记新代次并发布后台事件。
        Resume resume = taskSubmissionService.submitManualReparse(userId, resumeId);
        return toUploadResponse(resume);
    }

    /**
     * 只重试当前正式画像的辅助分析，不重新解析原简历文件。
     */
    public void retryProfileAnalysis(
            Long userId, Long resumeId, ResumeProfileAnalysisRetryRequest request) {
        // 手动分析会发送正式事实和可选用户反馈到模型，必须有当前 AI 处理同意。
        consentService.requireAiProcessingConsent(userId);
        // 提交服务校验模式并协调许可、额度、数据库任务和事件补偿。
        taskSubmissionService.submitManualAnalysis(userId, resumeId, request);
    }

    /**
     * 在同用户变更锁内删除用户拥有的数据库记录，再在事务提交后删除返回路径对应的物理文件。
     * 文件删除的 I/O 失败由存储服务记录后吞掉，数据库删除不会回滚；路径解析等运行时异常仍可能向上传播。
     */
    public void deleteResume(Long userId, Long resumeId) {
        // Redis 锁串行同一用户资源变更，数据库服务仍执行归属与简历锁定校验。
        String filePath = mutationLock.execute(
                userId, () -> persistenceService.deleteOwned(userId, resumeId));
        // 物理文件不属于数据库事务；只在数据库删除成功并返回路径后执行。
        fileStorageService.delete(filePath);
    }

    /** 只返回与简历当前解析代次一致的用户草稿，旧代次草稿视为不存在。 */
    private Optional<ResumeProfileDraft> currentDraft(Resume resume, Long userId) {
        return draftRepository.findByResumeIdAndUserId(resume.getId(), userId)
                .filter(draft -> draft.getParseGeneration().equals(resume.getParseGeneration()));
    }

    /** 要求客户端显式携带当前解析代次，空值和旧值都按草稿过期拒绝。 */
    private Long requireCurrentGeneration(Long requestedGeneration, Resume resume) {
        if (requestedGeneration == null || !requestedGeneration.equals(resume.getParseGeneration())) {
            throw new BusinessException(PROFILE_DRAFT_STALE, "画像草稿已过期，请刷新后重试");
        }
        return requestedGeneration;
    }

    /** 按当前用户归属查询简历；不存在和不属于该用户统一返回 RESUME_NOT_FOUND。 */
    private Resume findResumeByIdAndUserId(Long resumeId, Long userId) {
        return resumeRepository.findByIdAndUserId(resumeId, userId)
                .orElseThrow(() -> new BusinessException(RESUME_NOT_FOUND, "简历不存在"));
    }

    /** 按当前用户归属锁定简历供状态写入；不存在和越权使用同一公开错误。 */
    private Resume findResumeByIdAndUserIdForUpdate(Long resumeId, Long userId) {
        return resumeRepository.findByIdAndUserIdForUpdate(resumeId, userId)
                .orElseThrow(() -> new BusinessException(RESUME_NOT_FOUND, "简历不存在"));
    }

    /** 将当前页简历主记录和批量画像存在性映射为列表项，不触发额外数据库查询。 */
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

    /**
     * 将已登记的 PENDING 简历映射为上传/重解析响应，当前固定进度为 10。
     * 该值只表示任务已登记，不是实时百分比；精确取值依据缺失，调整会改变客户端初始进度展示。
     */
    private ResumeUploadResponse toUploadResponse(Resume resume) {
        return new ResumeUploadResponse(
                resume.getId(), resume.getResumeName(), resume.getParseStatus().name(),
                resume.getParseStatus().getDisplayName(), 10);
    }

    /**
     * 将离散解析状态映射为轮询展示值：失败 0、待执行 10、执行中 50、待确认或已确认 100。
     * 这些值不测量真实执行进度，0/10/50/100 的精确产品依据缺失；调整只改变当前 API 展示语义。
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
