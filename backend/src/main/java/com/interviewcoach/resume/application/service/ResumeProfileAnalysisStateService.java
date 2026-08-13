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
 * 为辅助分析提交服务、Worker 和查询接口提供数据库短事务状态边界。
 * 单行实体同时保留最近一次成功分析与最新任务状态，本服务通过任务代次和正式画像 hash 防止旧任务覆盖；
 * 模型调用与 Redis 额度结算均由事务外调用方负责。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ResumeProfileAnalysisStateService {

    /**
     * 当前成功分析 JSON 写入实体时使用的 Schema 版本标记。
     * 仓库未提供版本 1 的升级或兼容记录，精确取值依据缺失；调整会改变后续结果的持久化标记，但当前读取逻辑未按版本分流。
     */
    private static final int ANALYSIS_SCHEMA_VERSION = 1;

    /** 查询、锁定并保存辅助分析结果与任务状态的仓储。 */
    private final ResumeProfileAnalysisRepository analysisRepository;
    /** 读取当前用户正式画像及其事实 hash 的仓储。 */
    private final ResumeProfileRepository profileRepository;
    /** 读取简历解析状态以阻止与事实重解析并发的仓储。 */
    private final ResumeRepository resumeRepository;
    /** 将正式画像 JSON 还原为模型输入事实的组件。 */
    private final ResumeProfileSupport profileSupport;
    /** 序列化和解析辅助分析 JSON 的项目 ObjectMapper。 */
    private final ObjectMapper objectMapper;

    /**
     * 正式事实提交后仅在免费资格仍有效且没有执行中任务时登记可选 INITIAL。
     */
    @Transactional
    public Optional<ResumeProfileAnalysisRequestedEvent> prepareOptionalInitial(
            Long resumeId, Long userId, String taskProfileHash) {
        // 同时锁定用户拥有的简历、正式画像和分析单行，封闭确认后可选提交的并发窗口。
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
        // 资格仍有效时登记无额度凭据的 INITIAL；旧成功结果仍留在同一实体中但暂不可用于面试。
        ResumeProfileAnalysis prepared = prepareLocked(
                resumeId,
                userId,
                taskProfileHash,
                ResumeProfileAnalysisMode.INITIAL,
                null,
                null,
                analysis);
        // 返回尚未附加任务许可的内存事件，由提交服务附加许可并发布。
        return Optional.of(toRequestedEvent(prepared, null, false));
    }

    /**
     * 在占用许可或额度前只读校验公开模式，并确定本次是否仍属于免费 INITIAL。
     */
    @Transactional(readOnly = true)
    public RetryPlan previewRetry(
            Long resumeId, Long userId, ResumeProfileAnalysisMode requestedMode) {
        // 预检按用户归属读取简历、正式画像和分析单行，不接受客户端提供的归属信息。
        Resume resume = resumeRepository.findByIdAndUserId(resumeId, userId)
                .orElseThrow(() -> new BusinessException(ResumeErrorCode.RESUME_NOT_FOUND, "简历不存在"));
        ResumeProfile profile = profileRepository.findByResumeIdAndUserId(resumeId, userId)
                .orElseThrow(() -> new BusinessException(ResumeErrorCode.RESUME_NOT_FOUND, "正式画像不存在"));
        ResumeProfileAnalysis analysis = analysisRepository.findByResumeIdAndUserId(resumeId, userId)
                .orElse(null);
        // 事实解析或分析任务运行中时拒绝，避免同一简历并行进入两个 AI 流程。
        ensureNoRunningTask(resume, analysis);
        // 数据库仍保存额度 token 表示前次 Redis 结算未确认，必须失败关闭。
        ensureQuotaSettled(analysis);
        // REGENERATE 可能按首次调用标记降为免费 INITIAL；REFINE 还要求同 hash 的可解析旧结果。
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
        // 锁定三个用户归属对象，在实际登记前复查预检看到的模式与并发状态。
        Resume resume = resumeRepository.findByIdAndUserIdForUpdate(resumeId, userId)
                .orElseThrow(() -> new BusinessException(ResumeErrorCode.RESUME_NOT_FOUND, "简历不存在"));
        ResumeProfile profile = profileRepository.findByResumeIdAndUserIdForUpdate(resumeId, userId)
                .orElseThrow(() -> new BusinessException(ResumeErrorCode.RESUME_NOT_FOUND, "正式画像不存在"));
        ResumeProfileAnalysis analysis = analysisRepository.findByResumeIdAndUserIdForUpdate(resumeId, userId)
                .orElse(null);
        ensureNoRunningTask(resume, analysis);
        ensureQuotaSettled(analysis);
        // 用锁内最新实体重新推导任务模式，变化时拒绝并由上层补偿已占用的 Redis 资源。
        ResumeProfileAnalysisMode actualTaskMode = resolveTaskMode(requestedMode, profile, analysis);
        if (actualTaskMode != expectedTaskMode) {
            throw new BusinessException(
                    ResumeErrorCode.PROFILE_ANALYSIS_RETRY_NOT_ALLOWED,
                    "辅助分析状态已变化，请刷新后重试");
        }
        // INITIAL 必须无额度预留，收费的 REGENERATE/REFINE 必须有完整预留，防止资源归属错配。
        if ((actualTaskMode == ResumeProfileAnalysisMode.INITIAL && quotaReservation != null)
                || (actualTaskMode != ResumeProfileAnalysisMode.INITIAL && quotaReservation == null)) {
            throw new BusinessException(
                    ResumeErrorCode.RESUME_TASK_INFRASTRUCTURE_UNAVAILABLE,
                    "辅助分析额度状态不一致，请稍后重试");
        }
        // 登记任务代次、事实 hash、模式和恢复凭据；feedback 不写实体，只保存在随后返回的事件中。
        ResumeProfileAnalysis prepared = prepareLocked(
                resumeId,
                userId,
                profile.getProfileHash(),
                actualTaskMode,
                quotaReservation == null ? null : quotaReservation.quotaDate(),
                quotaReservation == null ? null : quotaReservation.quotaToken(),
                analysis);
        // 手动请求要求监听器必须成功交接，REFINE 才携带可能敏感的内存 feedback。
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
        // 锁定用户拥有的简历、正式画像和分析单行，原子校验任务与事实版本后再认领。
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
                // REFINE 必须读取同一正式画像 hash 下保留且可解析的上一次成功结果。
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
        // 写入 RUNNING 后提交短事务；模型调用不会持有这些数据库锁。
        analysisRepository.save(analysis);
        // 在事务内解析正式画像 JSON，损坏数据会使认领事务回滚并由 Worker 进入失败确认。
        UserProfileData data = profileSupport.fromJson(resumeId, profile.getProfileData());
        return new AnalysisInput(data, previousAnalysis, analysis.getTaskMode(), quotaReservation);
    }

    /**
     * 仅写回归属、任务代次、任务 hash 和当前正式事实 hash 全部匹配的 RUNNING 结果；quota 凭据留待统一结算器确认可清理后删除。
     */
    @Transactional
    public boolean complete(
            Long resumeId,
            Long userId,
            Long taskGeneration,
            String taskProfileHash,
            ResumeProfileAnalysisData data) {
        // 锁定正式画像与分析单行，结果只允许写回仍匹配当前事实 hash 的 RUNNING 任务。
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
        // 成功结果覆盖保留结果字段，并记录实际生成它的事实、Schema 和 Prompt 版本。
        analysis.setAnalysisData(toJson(data));
        analysis.setSourceProfileHash(taskProfileHash);
        analysis.setSchemaVersion(ANALYSIS_SCHEMA_VERSION);
        analysis.setPromptVersion(ResumeProfileAnalysisAgent.PROMPT_VERSION);
        analysis.setStatus(ResumeProfileAnalysisStatus.SUCCEEDED);
        analysis.setUsableForInterview(true);
        analysis.setGeneratedAt(LocalDateTime.now());
        analysis.setErrorCode(null);
        analysis.setErrorMessage(null);
        // 额度恢复凭据仍保留，Worker 在同日 Redis 转换成功或额度日期关闭后另开事务清理。
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
        // 兼容失败入口只按归属、代次和任务 hash 锁定当前任务，不参与 Redis 额度结算。
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
        // 严格匹配当前任务与数据库额度凭据，避免 Worker 为另一个代次结算 Redis 资源。
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
     * 统一结算器确认同日 Redis 转换成功或额度日期已关闭后，按归属、任务代次、事实 hash、日期和 token 幂等清理恢复凭据。
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
        // 结算器允许清理后再次锁定并匹配代次、事实 hash、日期和 token；错配请求不清任何数据。
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
        // 只删除恢复凭据，成功结果、失败状态和首次调用标记保持原值。
        analysisRepository.save(analysis);
    }

    /**
     * 在首次免费任务真正调用模型前幂等置位；任何失败或恢复路径都不得清除此标记。
     */
    @Transactional
    public boolean markInitialModelCallStarted(
            Long resumeId, Long userId, Long taskGeneration, String taskProfileHash) {
        // 在首次模型请求紧前锁定正式画像与任务，确保免费资格只被当前 INITIAL 消费。
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
            // 标记一旦提交，模型后续失败也不会恢复免费 INITIAL 资格。
            analysisRepository.save(analysis);
        }
        return true;
    }

    /**
     * 返回单行中保留的成功结果和最新任务状态；是否可用于面试或继续调整由视图显式计算。
     */
    @Transactional(readOnly = true)
    public Optional<AnalysisView> loadCurrent(Long resumeId, Long userId) {
        // 查询始终带用户归属；没有正式画像时不对外暴露孤立分析行。
        Resume resume = resumeRepository.findByIdAndUserId(resumeId, userId).orElse(null);
        ResumeProfile profile = profileRepository.findByResumeIdAndUserId(resumeId, userId).orElse(null);
        if (profile == null) {
            return Optional.empty();
        }
        // 同时返回保留结果、结果是否匹配当前事实和事实解析是否运行，供响应层计算可用性。
        return analysisRepository.findByResumeIdAndUserId(resumeId, userId)
                .map(analysis -> new AnalysisView(
                        analysis,
                        loadAnalysisData(analysis),
                        Objects.equals(analysis.getSourceProfileHash(), profile.getProfileHash()),
                        resume != null && (resume.getParseStatus() == ResumeParseStatus.PENDING
                                || resume.getParseStatus() == ResumeParseStatus.PARSING)));
    }

    /**
     * 尽力解析单行中保留的成功结果；空值或坏 JSON 返回 null，使公开视图降级为不可用而不阻断正式画像。
     */
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

    /**
     * 在调用方已持有数据库锁时新建或复用分析单行，递增任务代次并登记 PENDING。
     * 该方法不会覆盖旧 analysisData，但会先撤销其面试可用性，直到新任务成功写回。
     */
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
        // 保存新任务状态和可选 quota 恢复凭据，供 Worker 认领及进程启动恢复。
        return analysisRepository.save(analysis);
    }

    /** 将数据库任务转换为进程内事件；feedback 只进入事件，requiredHandoff 决定监听器拒绝语义。 */
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

    /**
     * 将公开请求解析为实际内部任务模式：REFINE 要求同事实版本旧结果，首次 REGENERATE 可使用免费 INITIAL。
     */
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

    /**
     * 读取与当前正式画像 hash 一致的保留成功结果；缺失、过期或坏 JSON 都拒绝 REFINE。
     */
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

    /** 判断事实解析或辅助分析是否有 PENDING/RUNNING 任务占用当前简历。 */
    private boolean isTaskRunning(Resume resume, ResumeProfileAnalysis analysis) {
        return resume.getParseStatus() == ResumeParseStatus.PENDING
                || resume.getParseStatus() == ResumeParseStatus.PARSING
                || analysis != null && (analysis.getStatus() == ResumeProfileAnalysisStatus.PENDING
                || analysis.getStatus() == ResumeProfileAnalysisStatus.RUNNING);
    }

    /** 当前简历已有任一 AI 任务时以稳定业务错误拒绝新的辅助分析。 */
    private void ensureNoRunningTask(Resume resume, ResumeProfileAnalysis analysis) {
        if (isTaskRunning(resume, analysis)) {
            throw new BusinessException(
                    ResumeErrorCode.RESUME_AI_CONCURRENCY_LIMIT,
                    "当前简历已有 AI 任务正在执行");
        }
    }

    /** 任一额度日期或 token 残留都表示前次任务结算尚未得到完整确认。 */
    private boolean hasUnsettledQuota(ResumeProfileAnalysis analysis) {
        return analysis != null
                && (analysis.getTaskQuotaDate() != null || analysis.getTaskQuotaToken() != null);
    }

    /** 前次额度恢复凭据未清时失败关闭，阻止新任务覆盖恢复上下文。 */
    private void ensureQuotaSettled(ResumeProfileAnalysis analysis) {
        if (hasUnsettledQuota(analysis)) {
            throw new BusinessException(
                    ResumeErrorCode.RESUME_TASK_INFRASTRUCTURE_UNAVAILABLE,
                    "上一辅助分析任务的额度仍在结算，请稍后重试");
        }
    }

    /** 精确比较数据库日期和 token 与调用方预留；双方都为空表示免费任务相符。 */
    private boolean matchesQuotaReservation(
            ResumeProfileAnalysis analysis, ResumeAiQuotaReservation expectedReservation) {
        if (expectedReservation == null) {
            return analysis.getTaskQuotaDate() == null && analysis.getTaskQuotaToken() == null;
        }
        return Objects.equals(analysis.getTaskQuotaDate(), expectedReservation.quotaDate())
                && Objects.equals(analysis.getTaskQuotaToken(), expectedReservation.quotaToken());
    }

    /** 只将 PENDING/RUNNING 当前任务标为失败并撤销面试可用性，保留旧成功 JSON 和额度凭据。 */
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

    /** 将模型辅助分析序列化为数据库 JSON；失败时阻止成功状态写入。 */
    private String toJson(ResumeProfileAnalysisData data) {
        try {
            return objectMapper.writeValueAsString(data);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("画像分析无法序列化", e);
        }
    }

    /** 将数据库保留结果还原为辅助分析对象；坏 JSON 统一转为可识别参数异常。 */
    private ResumeProfileAnalysisData fromJson(String json) {
        try {
            return objectMapper.readValue(json, ResumeProfileAnalysisData.class);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("画像分析数据损坏", e);
        }
    }

    /**
     * Worker 成功认领任务后在事务外调用模型所需的稳定输入。
     *
     * @param profileData 当前正式画像 JSON 还原出的已确认事实
     * @param previousAnalysis REFINE 使用的同事实版本旧成功结果，其他模式为 null
     * @param taskMode 锁内确认后的 INITIAL、REGENERATE 或 REFINE 实际模式
     * @param quotaReservation 手动收费任务的数据库恢复凭据；免费 INITIAL 为 null
     */
    public record AnalysisInput(
            UserProfileData profileData,
            ResumeProfileAnalysisData previousAnalysis,
            ResumeProfileAnalysisMode taskMode,
            ResumeAiQuotaReservation quotaReservation) {
    }

    /**
     * Redis 资源占用前的只读任务计划。
     *
     * @param taskMode 根据首次调用标记和保留结果推导出的实际内部模式
     * @param quotaRequired 实际模式不是免费 INITIAL 时为 true
     */
    public record RetryPlan(ResumeProfileAnalysisMode taskMode, boolean quotaRequired) {
    }

    /**
     * 聚合保留成功结果与最新任务状态的只读视图，供简历查询接口计算展示和操作资格。
     *
     * @param entity 同一用户、同一简历的分析单行，包含最新任务状态和保留结果元数据
     * @param data 可解析的保留成功结果；不存在或损坏时为 null
     * @param resultMatchesCurrentProfile 保留结果的来源 hash 与当前正式画像一致时为 true
     * @param resumeTaskRunning 当前简历事实解析处于 PENDING/PARSING 时为 true
     */
    public record AnalysisView(
            ResumeProfileAnalysis entity,
            ResumeProfileAnalysisData data,
            boolean resultMatchesCurrentProfile,
            boolean resumeTaskRunning) {

        /** 兼容只传实体和数据的内部构造，默认结果匹配且没有事实解析任务运行。 */
        public AnalysisView(ResumeProfileAnalysis entity, ResumeProfileAnalysisData data) {
            this(entity, data, true, false);
        }

        /** 只有结果存在、事实版本匹配、实体允许且最新任务成功时才可供面试使用。 */
        public boolean usableForInterview() {
            return data != null
                    && resultMatchesCurrentProfile
                    && entity.isUsableForInterview()
                    && entity.getStatus() == ResumeProfileAnalysisStatus.SUCCEEDED;
        }

        /** 有同版本保留结果、无事实解析且最新分析不在执行中时允许继续调整。 */
        public boolean refineAllowed() {
            return data != null
                    && resultMatchesCurrentProfile
                    && !resumeTaskRunning
                    && entity.getStatus() != ResumeProfileAnalysisStatus.PENDING
                    && entity.getStatus() != ResumeProfileAnalysisStatus.RUNNING;
        }

        /** 成功状态若没有可解析结果，对公开查询降级显示为 FAILED。 */
        public ResumeProfileAnalysisStatus effectiveStatus() {
            if (entity.getStatus() == ResumeProfileAnalysisStatus.SUCCEEDED && data == null) {
                return ResumeProfileAnalysisStatus.FAILED;
            }
            return entity.getStatus();
        }

        /** 成功状态若结果损坏，返回安全重试提示；其他状态沿用实体中的最新错误。 */
        public String effectiveErrorMessage() {
            if (entity.getStatus() == ResumeProfileAnalysisStatus.SUCCEEDED && data == null) {
                return "画像分析数据不可用，请手动重试";
            }
            return entity.getErrorMessage();
        }
    }
}
