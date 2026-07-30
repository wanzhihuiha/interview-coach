package com.interviewcoach.resume.application.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.interviewcoach.common.exception.BusinessException;
import com.interviewcoach.resume.application.event.ResumeProfileAnalysisRequestedEvent;
import com.interviewcoach.resume.application.event.ResumeAiQuotaReservation;
import com.interviewcoach.resume.domain.agent.ResumeProfileAnalysisAgent;
import com.interviewcoach.resume.domain.entity.Resume;
import com.interviewcoach.resume.domain.entity.ResumeParseStatus;
import com.interviewcoach.resume.domain.entity.ResumeProfile;
import com.interviewcoach.resume.domain.entity.ResumeProfileAnalysis;
import com.interviewcoach.resume.domain.entity.ResumeProfileAnalysisMode;
import com.interviewcoach.resume.domain.entity.ResumeProfileAnalysisStatus;
import com.interviewcoach.resume.domain.model.ResumeProfileAnalysisData;
import com.interviewcoach.resume.domain.model.UserProfileData;
import com.interviewcoach.resume.domain.repository.ResumeProfileAnalysisRepository;
import com.interviewcoach.resume.domain.repository.ResumeProfileRepository;
import com.interviewcoach.resume.domain.repository.ResumeRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 管理画像分析的短事务状态；模型调用由 Worker 在事务外执行。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ResumeProfileAnalysisStateService {

    private static final int ANALYSIS_SCHEMA_VERSION = 1;

    private final ResumeProfileAnalysisRepository analysisRepository;
    private final ResumeProfileRepository profileRepository;
    private final ResumeRepository resumeRepository;
    private final ResumeProfileSupport profileSupport;
    private final ObjectMapper objectMapper;

    /**
     * 正式事实提交后仅在免费资格仍有效且没有执行中任务时登记可选 INITIAL。
     */
    @Transactional
    public Optional<ResumeProfileAnalysisRequestedEvent> prepareOptionalInitial(
            Long resumeId, Long userId, String taskProfileHash) {
        Resume resume = resumeRepository.findByIdAndUserIdForUpdate(resumeId, userId)
                .orElseThrow(() -> new BusinessException(ResumeErrorCode.RESUME_NOT_FOUND, "简历不存在"));
        ResumeProfile profile = profileRepository.findByResumeIdAndUserIdForUpdate(resumeId, userId)
                .orElseThrow(() -> new BusinessException(ResumeErrorCode.RESUME_NOT_FOUND, "正式画像不存在"));
        ResumeProfileAnalysis analysis = analysisRepository
                .findByResumeIdAndUserIdForUpdate(resumeId, userId)
                .orElse(null);
        if (!Objects.equals(profile.getProfileHash(), taskProfileHash)
                || isTaskRunning(resume, analysis)
                || hasUnsettledQuota(analysis)
                || (analysis != null && analysis.isInitialModelCallStarted())) {
            return Optional.empty();
        }
        ResumeProfileAnalysis prepared = prepareLocked(
                resumeId,
                userId,
                taskProfileHash,
                ResumeProfileAnalysisMode.INITIAL,
                null,
                null,
                analysis);
        return Optional.of(toRequestedEvent(prepared, null, false));
    }

    /**
     * 在占用许可或额度前只读校验公开模式，并确定本次是否仍属于免费 INITIAL。
     */
    @Transactional(readOnly = true)
    public RetryPlan previewRetry(
            Long resumeId, Long userId, ResumeProfileAnalysisMode requestedMode) {
        Resume resume = resumeRepository.findByIdAndUserId(resumeId, userId)
                .orElseThrow(() -> new BusinessException(ResumeErrorCode.RESUME_NOT_FOUND, "简历不存在"));
        ResumeProfile profile = profileRepository.findByResumeIdAndUserId(resumeId, userId)
                .orElseThrow(() -> new BusinessException(ResumeErrorCode.RESUME_NOT_FOUND, "正式画像不存在"));
        ResumeProfileAnalysis analysis = analysisRepository.findByResumeIdAndUserId(resumeId, userId)
                .orElse(null);
        ensureNoRunningTask(resume, analysis);
        ensureQuotaSettled(analysis);
        ResumeProfileAnalysisMode taskMode = resolveTaskMode(requestedMode, profile, analysis);
        return new RetryPlan(taskMode, taskMode != ResumeProfileAnalysisMode.INITIAL);
    }

    /**
     * 取得许可和必要额度后锁内复查公开模式，再登记任务；feedback 只进入返回事件的内存上下文。
     */
    @Transactional
    public ResumeProfileAnalysisRequestedEvent prepareRetry(
            Long resumeId,
            Long userId,
            ResumeProfileAnalysisMode requestedMode,
            ResumeProfileAnalysisMode expectedTaskMode,
            ResumeAiQuotaReservation quotaReservation,
            String feedback) {
        Resume resume = resumeRepository.findByIdAndUserIdForUpdate(resumeId, userId)
                .orElseThrow(() -> new BusinessException(ResumeErrorCode.RESUME_NOT_FOUND, "简历不存在"));
        ResumeProfile profile = profileRepository.findByResumeIdAndUserIdForUpdate(resumeId, userId)
                .orElseThrow(() -> new BusinessException(ResumeErrorCode.RESUME_NOT_FOUND, "正式画像不存在"));
        ResumeProfileAnalysis analysis = analysisRepository.findByResumeIdAndUserIdForUpdate(resumeId, userId)
                .orElse(null);
        ensureNoRunningTask(resume, analysis);
        ensureQuotaSettled(analysis);
        ResumeProfileAnalysisMode actualTaskMode = resolveTaskMode(requestedMode, profile, analysis);
        if (actualTaskMode != expectedTaskMode) {
            throw new BusinessException(
                    ResumeErrorCode.PROFILE_ANALYSIS_RETRY_NOT_ALLOWED,
                    "辅助分析状态已变化，请刷新后重试");
        }
        if ((actualTaskMode == ResumeProfileAnalysisMode.INITIAL && quotaReservation != null)
                || (actualTaskMode != ResumeProfileAnalysisMode.INITIAL && quotaReservation == null)) {
            throw new BusinessException(
                    ResumeErrorCode.RESUME_TASK_INFRASTRUCTURE_UNAVAILABLE,
                    "辅助分析额度状态不一致，请稍后重试");
        }
        ResumeProfileAnalysis prepared = prepareLocked(
                resumeId,
                userId,
                profile.getProfileHash(),
                actualTaskMode,
                quotaReservation == null ? null : quotaReservation.quotaDate(),
                quotaReservation == null ? null : quotaReservation.quotaToken(),
                analysis);
        return toRequestedEvent(
                prepared,
                actualTaskMode == ResumeProfileAnalysisMode.REFINE ? feedback : null,
                true);
    }

    /**
     * 将匹配当前正式画像 hash 的 PENDING 任务切换为 RUNNING，并返回事务外模型调用所需事实。
     */
    @Transactional
    public AnalysisInput start(
            Long resumeId, Long userId, Long taskGeneration, String taskProfileHash) {
        Resume resume = resumeRepository.findByIdAndUserIdForUpdate(resumeId, userId)
                .orElse(null);
        ResumeProfile profile = profileRepository.findByResumeIdAndUserIdForUpdate(resumeId, userId)
                .orElse(null);
        ResumeProfileAnalysis analysis = analysisRepository.findByResumeIdAndUserIdForUpdate(resumeId, userId)
                .orElse(null);
        if (analysis == null || profile == null || resume == null
                || resume.getParseStatus() == ResumeParseStatus.PENDING
                || resume.getParseStatus() == ResumeParseStatus.PARSING
                || analysis.getStatus() != ResumeProfileAnalysisStatus.PENDING
                || !Objects.equals(analysis.getTaskGeneration(), taskGeneration)
                || !Objects.equals(analysis.getTaskProfileHash(), taskProfileHash)
                || !Objects.equals(profile.getProfileHash(), taskProfileHash)) {
            return null;
        }
        ResumeProfileAnalysisData previousAnalysis = analysis.getTaskMode() == ResumeProfileAnalysisMode.REFINE
                ? requireRetainedAnalysis(profile, analysis)
                : null;
        ResumeAiQuotaReservation quotaReservation = analysis.getTaskQuotaDate() == null
                || analysis.getTaskQuotaToken() == null
                ? null
                : new ResumeAiQuotaReservation(
                        analysis.getTaskQuotaDate(), analysis.getTaskQuotaToken());
        analysis.setStatus(ResumeProfileAnalysisStatus.RUNNING);
        analysis.setErrorCode(null);
        analysis.setErrorMessage(null);
        analysisRepository.save(analysis);
        UserProfileData data = profileSupport.fromJson(resumeId, profile.getProfileData());
        return new AnalysisInput(data, previousAnalysis, analysis.getTaskMode(), quotaReservation);
    }

    /**
     * 仅写回归属、任务代次、任务 hash 和当前正式事实 hash 全部匹配的 RUNNING 结果；quota 凭据留待 Redis 结算后清理。
     */
    @Transactional
    public boolean complete(
            Long resumeId,
            Long userId,
            Long taskGeneration,
            String taskProfileHash,
            ResumeProfileAnalysisData data) {
        ResumeProfile profile = profileRepository.findByResumeIdAndUserIdForUpdate(resumeId, userId)
                .orElse(null);
        ResumeProfileAnalysis analysis = analysisRepository.findByResumeIdAndUserIdForUpdate(resumeId, userId)
                .orElse(null);
        if (analysis == null || profile == null
                || analysis.getStatus() != ResumeProfileAnalysisStatus.RUNNING
                || !Objects.equals(analysis.getTaskGeneration(), taskGeneration)
                || !Objects.equals(analysis.getTaskProfileHash(), taskProfileHash)
                || !Objects.equals(profile.getProfileHash(), taskProfileHash)) {
            log.info(
                    "[ResumeProfileAnalysis] 丢弃过期分析结果: resumeId={}, generation={}",
                    resumeId,
                    taskGeneration);
            return false;
        }
        analysis.setAnalysisData(toJson(data));
        analysis.setSourceProfileHash(taskProfileHash);
        analysis.setSchemaVersion(ANALYSIS_SCHEMA_VERSION);
        analysis.setPromptVersion(ResumeProfileAnalysisAgent.PROMPT_VERSION);
        analysis.setStatus(ResumeProfileAnalysisStatus.SUCCEEDED);
        analysis.setUsableForInterview(true);
        analysis.setGeneratedAt(LocalDateTime.now());
        analysis.setErrorCode(null);
        analysis.setErrorMessage(null);
        analysisRepository.save(analysis);
        return true;
    }

    /**
     * 兼容旧调用：只失败当前代次和任务 hash，保留旧成功结果、首次调用标记和 quota 恢复凭据。
     */
    @Transactional
    public void fail(
            Long resumeId,
            Long userId,
            Long taskGeneration,
            String taskProfileHash,
            String errorCode,
            String errorMessage) {
        ResumeProfileAnalysis analysis = analysisRepository.findByResumeIdAndUserIdForUpdate(resumeId, userId)
                .orElse(null);
        if (analysis == null
                || !Objects.equals(analysis.getTaskGeneration(), taskGeneration)
                || !Objects.equals(analysis.getTaskProfileHash(), taskProfileHash)) {
            return;
        }
        failRunningTask(analysis, errorCode, errorMessage);
    }

    /**
     * 严格按任务和 quota 恢复凭据确认数据库终态；终态未知时调用方不得结算 Redis。
     */
    @Transactional
    public ResumeAiTaskOutcome fail(
            Long resumeId,
            Long userId,
            Long taskGeneration,
            String taskProfileHash,
            ResumeAiQuotaReservation expectedReservation,
            String errorCode,
            String errorMessage) {
        ResumeProfileAnalysis analysis = analysisRepository.findByResumeIdAndUserIdForUpdate(resumeId, userId)
                .orElse(null);
        if (analysis == null
                || !Objects.equals(analysis.getTaskGeneration(), taskGeneration)
                || !Objects.equals(analysis.getTaskProfileHash(), taskProfileHash)
                || !matchesQuotaReservation(analysis, expectedReservation)) {
            return ResumeAiTaskOutcome.UNKNOWN;
        }
        if (analysis.getStatus() == ResumeProfileAnalysisStatus.SUCCEEDED) {
            return ResumeAiTaskOutcome.SUCCESS_CONFIRMED;
        }
        if (analysis.getStatus() == ResumeProfileAnalysisStatus.FAILED) {
            return ResumeAiTaskOutcome.FAILURE_CONFIRMED;
        }
        if (analysis.getStatus() == ResumeProfileAnalysisStatus.PENDING
                || analysis.getStatus() == ResumeProfileAnalysisStatus.RUNNING) {
            failRunningTask(analysis, errorCode, errorMessage);
            return ResumeAiTaskOutcome.FAILURE_CONFIRMED;
        }
        return ResumeAiTaskOutcome.UNKNOWN;
    }

    /**
     * Redis 已确认结算后，按归属、任务代次、事实 hash、日期和 token 幂等清理恢复凭据。
     */
    @Transactional
    public void clearQuotaReservation(
            Long resumeId,
            Long userId,
            Long taskGeneration,
            String taskProfileHash,
            ResumeAiQuotaReservation expectedReservation) {
        if (expectedReservation == null) {
            return;
        }
        ResumeProfileAnalysis analysis = analysisRepository.findByResumeIdAndUserIdForUpdate(resumeId, userId)
                .orElse(null);
        if (analysis == null
                || !Objects.equals(analysis.getTaskGeneration(), taskGeneration)
                || !Objects.equals(analysis.getTaskProfileHash(), taskProfileHash)
                || !matchesQuotaReservation(analysis, expectedReservation)) {
            return;
        }
        analysis.setTaskQuotaDate(null);
        analysis.setTaskQuotaToken(null);
        analysisRepository.save(analysis);
    }

    /**
     * 在首次免费任务真正调用模型前幂等置位；任何失败或恢复路径都不得清除此标记。
     */
    @Transactional
    public boolean markInitialModelCallStarted(
            Long resumeId, Long userId, Long taskGeneration, String taskProfileHash) {
        ResumeProfile profile = profileRepository.findByResumeIdAndUserIdForUpdate(resumeId, userId)
                .orElse(null);
        ResumeProfileAnalysis analysis = analysisRepository.findByResumeIdAndUserIdForUpdate(resumeId, userId)
                .orElse(null);
        if (analysis == null || profile == null
                || analysis.getStatus() != ResumeProfileAnalysisStatus.RUNNING
                || analysis.getTaskMode() != ResumeProfileAnalysisMode.INITIAL
                || !Objects.equals(analysis.getTaskGeneration(), taskGeneration)
                || !Objects.equals(analysis.getTaskProfileHash(), taskProfileHash)
                || !Objects.equals(profile.getProfileHash(), taskProfileHash)) {
            return false;
        }
        if (!analysis.isInitialModelCallStarted()) {
            analysis.setInitialModelCallStarted(true);
            analysisRepository.save(analysis);
        }
        return true;
    }

    /**
     * 返回单行中保留的成功结果和最新任务状态；是否可用于面试或继续调整由视图显式计算。
     */
    @Transactional(readOnly = true)
    public Optional<AnalysisView> loadCurrent(Long resumeId, Long userId) {
        Resume resume = resumeRepository.findByIdAndUserId(resumeId, userId).orElse(null);
        ResumeProfile profile = profileRepository.findByResumeIdAndUserId(resumeId, userId).orElse(null);
        if (profile == null) {
            return Optional.empty();
        }
        return analysisRepository.findByResumeIdAndUserId(resumeId, userId)
                .map(analysis -> new AnalysisView(
                        analysis,
                        loadAnalysisData(analysis),
                        Objects.equals(analysis.getSourceProfileHash(), profile.getProfileHash()),
                        resume != null && (resume.getParseStatus() == ResumeParseStatus.PENDING
                                || resume.getParseStatus() == ResumeParseStatus.PARSING)));
    }

    private ResumeProfileAnalysisData loadAnalysisData(ResumeProfileAnalysis analysis) {
        if (analysis.getAnalysisData() == null || analysis.getAnalysisData().isBlank()) {
            return null;
        }
        try {
            return fromJson(analysis.getAnalysisData());
        } catch (IllegalArgumentException e) {
            // AI 分析是可选线索，损坏数据不能阻断正式画像查询或面试创建。
            log.warn("[ResumeProfileAnalysis] 已忽略损坏的分析数据: resumeId={}, errorType={}",
                    analysis.getResumeId(), e.getClass().getSimpleName());
            return null;
        }
    }

    private ResumeProfileAnalysis prepareLocked(
            Long resumeId,
            Long userId,
            String taskProfileHash,
            ResumeProfileAnalysisMode taskMode,
            LocalDate taskQuotaDate,
            String taskQuotaToken,
            ResumeProfileAnalysis existing) {
        Objects.requireNonNull(taskProfileHash, "taskProfileHash must not be null");
        Objects.requireNonNull(taskMode, "taskMode must not be null");
        ensureQuotaSettled(existing);
        ResumeProfileAnalysis analysis = existing;
        if (analysis == null) {
            analysis = new ResumeProfileAnalysis();
            analysis.setResumeId(resumeId);
            analysis.setUserId(userId);
            analysis.setPromptVersion(ResumeProfileAnalysisAgent.PROMPT_VERSION);
        }
        analysis.setTaskGeneration(Math.addExact(analysis.getTaskGeneration(), 1L));
        analysis.setTaskProfileHash(taskProfileHash);
        analysis.setTaskMode(taskMode);
        analysis.setTaskQuotaDate(taskQuotaDate);
        analysis.setTaskQuotaToken(taskQuotaToken);
        analysis.setStatus(ResumeProfileAnalysisStatus.PENDING);
        analysis.setUsableForInterview(false);
        analysis.setErrorCode(null);
        analysis.setErrorMessage(null);
        return analysisRepository.save(analysis);
    }

    private ResumeProfileAnalysisRequestedEvent toRequestedEvent(
            ResumeProfileAnalysis analysis, String feedback, boolean requiredHandoff) {
        ResumeAiQuotaReservation quotaReservation = analysis.getTaskQuotaDate() == null
                || analysis.getTaskQuotaToken() == null
                ? null
                : new ResumeAiQuotaReservation(
                        analysis.getTaskQuotaDate(), analysis.getTaskQuotaToken());
        return new ResumeProfileAnalysisRequestedEvent(
                analysis.getResumeId(),
                analysis.getUserId(),
                analysis.getTaskGeneration(),
                analysis.getTaskProfileHash(),
                null,
                quotaReservation,
                feedback,
                requiredHandoff);
    }

    private ResumeProfileAnalysisMode resolveTaskMode(
            ResumeProfileAnalysisMode requestedMode,
            ResumeProfile profile,
            ResumeProfileAnalysis analysis) {
        if (requestedMode == ResumeProfileAnalysisMode.REFINE) {
            requireRetainedAnalysis(profile, analysis);
            return ResumeProfileAnalysisMode.REFINE;
        }
        if (requestedMode != ResumeProfileAnalysisMode.REGENERATE) {
            throw new BusinessException(
                    ResumeErrorCode.PROFILE_ANALYSIS_REQUEST_INVALID, "辅助分析模式不合法");
        }
        return analysis == null || !analysis.isInitialModelCallStarted()
                ? ResumeProfileAnalysisMode.INITIAL
                : ResumeProfileAnalysisMode.REGENERATE;
    }

    private ResumeProfileAnalysisData requireRetainedAnalysis(
            ResumeProfile profile, ResumeProfileAnalysis analysis) {
        if (analysis == null
                || analysis.getAnalysisData() == null
                || analysis.getAnalysisData().isBlank()
                || !Objects.equals(analysis.getSourceProfileHash(), profile.getProfileHash())) {
            throw new BusinessException(
                    ResumeErrorCode.PROFILE_ANALYSIS_REFINE_NOT_ALLOWED,
                    "当前没有可用于调整的同版本成功分析");
        }
        try {
            return fromJson(analysis.getAnalysisData());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(
                    ResumeErrorCode.PROFILE_ANALYSIS_REFINE_NOT_ALLOWED,
                    "当前保留的辅助分析不可用，请完全重新生成");
        }
    }

    private boolean isTaskRunning(Resume resume, ResumeProfileAnalysis analysis) {
        return resume.getParseStatus() == ResumeParseStatus.PENDING
                || resume.getParseStatus() == ResumeParseStatus.PARSING
                || analysis != null && (analysis.getStatus() == ResumeProfileAnalysisStatus.PENDING
                || analysis.getStatus() == ResumeProfileAnalysisStatus.RUNNING);
    }

    private void ensureNoRunningTask(Resume resume, ResumeProfileAnalysis analysis) {
        if (isTaskRunning(resume, analysis)) {
            throw new BusinessException(
                    ResumeErrorCode.RESUME_AI_CONCURRENCY_LIMIT,
                    "当前简历已有 AI 任务正在执行");
        }
    }

    private boolean hasUnsettledQuota(ResumeProfileAnalysis analysis) {
        return analysis != null
                && (analysis.getTaskQuotaDate() != null || analysis.getTaskQuotaToken() != null);
    }

    private void ensureQuotaSettled(ResumeProfileAnalysis analysis) {
        if (hasUnsettledQuota(analysis)) {
            throw new BusinessException(
                    ResumeErrorCode.RESUME_TASK_INFRASTRUCTURE_UNAVAILABLE,
                    "上一辅助分析任务的额度仍在结算，请稍后重试");
        }
    }

    private boolean matchesQuotaReservation(
            ResumeProfileAnalysis analysis, ResumeAiQuotaReservation expectedReservation) {
        if (expectedReservation == null) {
            return analysis.getTaskQuotaDate() == null && analysis.getTaskQuotaToken() == null;
        }
        return Objects.equals(analysis.getTaskQuotaDate(), expectedReservation.quotaDate())
                && Objects.equals(analysis.getTaskQuotaToken(), expectedReservation.quotaToken());
    }

    private void failRunningTask(
            ResumeProfileAnalysis analysis, String errorCode, String errorMessage) {
        if (analysis.getStatus() != ResumeProfileAnalysisStatus.PENDING
                && analysis.getStatus() != ResumeProfileAnalysisStatus.RUNNING) {
            return;
        }
        analysis.setStatus(ResumeProfileAnalysisStatus.FAILED);
        analysis.setUsableForInterview(false);
        analysis.setErrorCode(errorCode);
        analysis.setErrorMessage(errorMessage);
        analysisRepository.save(analysis);
    }

    private String toJson(ResumeProfileAnalysisData data) {
        try {
            return objectMapper.writeValueAsString(data);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("画像分析无法序列化", e);
        }
    }

    private ResumeProfileAnalysisData fromJson(String json) {
        try {
            return objectMapper.readValue(json, ResumeProfileAnalysisData.class);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("画像分析数据损坏", e);
        }
    }

    public record AnalysisInput(
            UserProfileData profileData,
            ResumeProfileAnalysisData previousAnalysis,
            ResumeProfileAnalysisMode taskMode,
            ResumeAiQuotaReservation quotaReservation) {
    }

    public record RetryPlan(ResumeProfileAnalysisMode taskMode, boolean quotaRequired) {
    }

    public record AnalysisView(
            ResumeProfileAnalysis entity,
            ResumeProfileAnalysisData data,
            boolean resultMatchesCurrentProfile,
            boolean resumeTaskRunning) {

        public AnalysisView(ResumeProfileAnalysis entity, ResumeProfileAnalysisData data) {
            this(entity, data, true, false);
        }

        public boolean usableForInterview() {
            return data != null
                    && resultMatchesCurrentProfile
                    && entity.isUsableForInterview()
                    && entity.getStatus() == ResumeProfileAnalysisStatus.SUCCEEDED;
        }

        public boolean refineAllowed() {
            return data != null
                    && resultMatchesCurrentProfile
                    && !resumeTaskRunning
                    && entity.getStatus() != ResumeProfileAnalysisStatus.PENDING
                    && entity.getStatus() != ResumeProfileAnalysisStatus.RUNNING;
        }

        public ResumeProfileAnalysisStatus effectiveStatus() {
            if (entity.getStatus() == ResumeProfileAnalysisStatus.SUCCEEDED && data == null) {
                return ResumeProfileAnalysisStatus.FAILED;
            }
            return entity.getStatus();
        }

        public String effectiveErrorMessage() {
            if (entity.getStatus() == ResumeProfileAnalysisStatus.SUCCEEDED && data == null) {
                return "画像分析数据不可用，请手动重试";
            }
            return entity.getErrorMessage();
        }
    }
}
