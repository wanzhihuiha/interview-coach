package com.interviewcoach.resume.application.service;

import com.interviewcoach.common.exception.BusinessException;
import com.interviewcoach.resume.application.dto.ResumeProfileAnalysisRetryRequest;
import com.interviewcoach.resume.application.event.ResumeAiQuotaReservation;
import com.interviewcoach.resume.application.event.ResumeAiTaskLease;
import com.interviewcoach.resume.application.event.ResumeParseRequestedEvent;
import com.interviewcoach.resume.application.event.ResumeProfileAnalysisRequestedEvent;
import com.interviewcoach.resume.application.service.ResumeParseStateService.PreparedParse;
import com.interviewcoach.resume.application.service.ResumePersistenceService.ConfirmedResume;
import com.interviewcoach.resume.application.service.ResumeProfileAnalysisStateService.RetryPlan;
import com.interviewcoach.resume.domain.entity.Resume;
import com.interviewcoach.resume.domain.entity.ResumeProfileAnalysisMode;
import com.interviewcoach.resume.infrastructure.redis.ResumeAiQuotaService;
import com.interviewcoach.resume.infrastructure.redis.ResumeAiTaskAdmissionService;
import com.interviewcoach.resume.infrastructure.redis.ResumeAiTaskLeaseRunner;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

/**
 * 接收简历应用服务发起的事实重解析、辅助分析重试和可选首次分析请求。
 * 本服务先协调 Redis 许可与额度，再调用状态服务登记数据库任务并发布进程内事件；
 * 提交中断时按数据库可确认的终态逆向释放资源，实际模型执行由对应 Worker 继续处理。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ResumeAiTaskSubmissionService {

    /**
     * REFINE 用户意见的当前字符上限；超过后在占用许可和额度前拒绝，避免继续扩大模型输入。
     * 精确取值 1000 的产品或容量依据缺失；调大将允许更多敏感反馈进入后续内存事件和模型提示词，调小会拒绝现有长度区间的请求。
     */
    private static final int MAX_FEEDBACK_LENGTH = 1000;

    /** 推进事实解析代次、数据库状态及额度恢复凭据的状态服务。 */
    private final ResumeParseStateService parseStateService;
    /** 推进辅助分析任务、保留结果及额度恢复凭据的状态服务。 */
    private final ResumeProfileAnalysisStateService analysisStateService;
    /** 在 Redis 中申请同用户、同简历 AI 任务许可的准入服务。 */
    private final ResumeAiTaskAdmissionService admissionService;
    /** 在任务尚未进入 Worker 时释放已取得许可的执行器。 */
    private final ResumeAiTaskLeaseRunner leaseRunner;
    /** 预留和结算用户每日 AI 调用额度的 Redis 服务。 */
    private final ResumeAiQuotaService quotaService;
    /** 将已登记任务交给事务后监听器和后台 Worker 的 Spring 事件发布器。 */
    private final ApplicationEventPublisher eventPublisher;

    /**
     * 手动事实重解析先取得许可和额度再登记代次；异常时只结算数据库能够明确确认的结果。
     */
    public Resume submitManualReparse(Long userId, Long resumeId) {
        // 先按归属、锁定状态和未结算额度校验资格，避免无效请求提前占用 Redis 资源。
        parseStateService.validateManualReparse(resumeId, userId);
        // 为该用户和简历占用 AI 并发许可；取得后任何后续异常都必须显式释放。
        ResumeAiTaskLease lease = admissionService.acquire(userId, resumeId);
        ResumeAiQuotaReservation reservation = null;
        ResumeParseRequestedEvent event = null;
        try {
            // 预留当天 AI 额度，返回的日期与 token 会随任务写入数据库供失败恢复使用。
            reservation = quotaService.reserve(userId);
            // 在短事务内锁定简历、增加解析代次并登记 PENDING，防止旧任务覆盖新草稿。
            PreparedParse prepared = parseStateService.prepareManualReparse(
                    resumeId, userId, reservation);
            // 将许可附到不可变事件后发布；监听器据此续期许可并把任务交给 Worker。
            event = prepared.event().withTaskLease(lease);
            eventPublisher.publishEvent(event);
            return prepared.resume();
        } catch (RuntimeException e) {
            ResumeParseRequestedEvent failedEvent = event;
            ResumeAiQuotaReservation failedReservation = reservation;
            // 先确认数据库是否已登记任务及其失败终态，结论决定额度可退回、消费或必须保留。
            ResumeAiTaskOutcome outcome = confirmParseSubmissionFailure(
                    resumeId, userId, failedEvent, failedReservation, e);
            // 任务未成功交给 Worker，由当前提交线程负责释放已占用的并发许可。
            releaseLeaseQuietly(userId, resumeId, lease, "manual-parse");
            // 最后按已确认终态结算额度；Redis 或数据库清理不确定时保留凭据给启动恢复流程。
            settleSubmissionQuota(
                    userId,
                    resumeId,
                    failedReservation,
                    outcome,
                    failedEvent == null
                            ? () -> { }
                            : () -> parseStateService.clearQuotaReservation(
                                    resumeId,
                                    userId,
                                    failedEvent.generation(),
                                    failedReservation),
                    "manual-parse");
            throw e;
        }
    }

    /**
     * 公开模式校验完成后按“许可 -> 必要额度 -> 锁内复查/登记 -> 事件交接”提交手动分析。
     */
    public void submitManualAnalysis(
            Long userId, Long resumeId, ResumeProfileAnalysisRetryRequest request) {
        // 在任何资源占用前解析公开模式、校验 REFINE 反馈并删除首尾空白。
        ValidatedRetry validated = validateRetryRequest(request);
        // 只读预检当前正式画像和保留结果，确定请求实际落为免费 INITIAL 还是需额度的重试。
        RetryPlan plan = analysisStateService.previewRetry(
                resumeId, userId, validated.requestedMode());
        // 预检通过后占用同用户、同简历的 AI 并发许可。
        ResumeAiTaskLease lease = admissionService.acquire(userId, resumeId);
        ResumeAiQuotaReservation reservation = null;
        ResumeProfileAnalysisRequestedEvent event = null;
        try {
            if (plan.quotaRequired()) {
                // 只有实际任务不是首次免费调用时才预留当天 AI 额度。
                reservation = quotaService.reserve(userId);
            }
            // 锁内重查模式和并发状态并登记任务；敏感 feedback 仅放入返回事件，不写入数据库。
            event = analysisStateService.prepareRetry(
                    resumeId,
                    userId,
                    validated.requestedMode(),
                    plan.taskMode(),
                    reservation,
                    validated.feedback());
            // 把许可附到事件并交给后台监听器；发布异常进入下方逆向补偿。
            event = event.withTaskLease(lease);
            eventPublisher.publishEvent(event);
        } catch (RuntimeException e) {
            ResumeProfileAnalysisRequestedEvent failedEvent = event;
            ResumeAiQuotaReservation failedReservation = reservation;
            // 数据库若已登记任务则尝试写入失败终态，否则按异常类型判断是否能确认未登记。
            ResumeAiTaskOutcome outcome = confirmAnalysisSubmissionFailure(
                    resumeId, userId, failedEvent, failedReservation, e);
            // 发布未完成时许可仍归提交线程，先释放许可再处理额度恢复。
            releaseLeaseQuietly(userId, resumeId, lease, "manual-analysis");
            // 按数据库终态处理额度；同日 Redis 转换成功或额度日期已关闭后才清理数据库 token。
            settleSubmissionQuota(
                    userId,
                    resumeId,
                    failedReservation,
                    outcome,
                    failedEvent == null
                            ? () -> { }
                            : () -> analysisStateService.clearQuotaReservation(
                                    resumeId,
                                    userId,
                                    failedEvent.taskGeneration(),
                                    failedEvent.taskProfileHash(),
                                    failedReservation),
                    "manual-analysis");
            throw e;
        }
    }

    /**
     * 正式事实已经提交成功；任何许可、登记或调度失败都只跳过可选 INITIAL，不反向影响确认结果。
     */
    public void submitOptionalInitial(Long userId, ConfirmedResume confirmed) {
        if (!confirmed.initialEligible()) {
            return;
        }
        ResumeAiTaskLease lease = null;
        ResumeProfileAnalysisRequestedEvent event = null;
        try {
            // 正式事实已经提交，先尝试取得可选任务许可；失败只影响本次辅助分析。
            lease = admissionService.acquire(userId, confirmed.resumeId());
            ResumeAiTaskLease acquiredLease = lease;
            // 锁内再次检查正式画像 hash、运行中任务和首次资格；不满足时返回空且不登记任务。
            event = analysisStateService.prepareOptionalInitial(
                            confirmed.resumeId(), userId, confirmed.profileHash())
                    .map(item -> item.withTaskLease(acquiredLease))
                    .orElse(null);
            if (event == null) {
                // 未登记任务时立即归还许可，已确认的正式画像保持不变。
                releaseLeaseQuietly(userId, confirmed.resumeId(), lease, "optional-initial");
                return;
            }
            // 已登记的 INITIAL 事件携带许可交给后台 Worker；该免费路径不携带额度预留。
            eventPublisher.publishEvent(event);
        } catch (RuntimeException e) {
            if (event != null) {
                try {
                    // 事件已登记但未成功交接时，将当前任务标为失败；旧成功分析结果仍由状态服务保留。
                    analysisStateService.fail(
                            confirmed.resumeId(),
                            userId,
                            event.taskGeneration(),
                            event.taskProfileHash(),
                            null,
                            "SCHEDULING_FAILED",
                            "首次辅助分析任务提交失败，请手动重试");
                } catch (RuntimeException confirmationError) {
                    log.warn(
                            "[ResumeProfileAnalysis] 可选 INITIAL 数据库终态无法确认: "
                                    + "resumeId={}, errorType={}",
                            confirmed.resumeId(),
                            confirmationError.getClass().getSimpleName());
                }
            }
            releaseLeaseQuietly(userId, confirmed.resumeId(), lease, "optional-initial");
            if (e instanceof BusinessException businessException) {
                log.info(
                        "[ResumeProfileAnalysis] 正式事实已确认，可选 INITIAL 已跳过: resumeId={}, code={}",
                        confirmed.resumeId(),
                        businessException.getCode());
            } else {
                log.warn(
                        "[ResumeProfileAnalysis] 正式事实已确认，可选 INITIAL 提交失败: resumeId={}, errorType={}",
                        confirmed.resumeId(),
                        e.getClass().getSimpleName());
            }
        }
    }

    /**
     * 将公开请求收敛为内部任务模式和可传给模型的反馈。
     * INITIAL 不允许从公开重试入口指定；REFINE 必须有不超过当前上限的非空反馈，其他模式丢弃反馈。
     */
    private ValidatedRetry validateRetryRequest(ResumeProfileAnalysisRetryRequest request) {
        if (request == null || request.mode() == null) {
            throw invalidRequest("辅助分析模式不能为空");
        }
        ResumeProfileAnalysisMode requestedMode;
        try {
            requestedMode = ResumeProfileAnalysisMode.valueOf(request.mode());
        } catch (IllegalArgumentException e) {
            throw invalidRequest("辅助分析模式只支持 REGENERATE 或 REFINE");
        }
        if (requestedMode == ResumeProfileAnalysisMode.INITIAL) {
            throw invalidRequest("INITIAL 不是公开辅助分析模式");
        }
        String feedback = request.feedback();
        if (requestedMode == ResumeProfileAnalysisMode.REFINE) {
            if (feedback == null || feedback.isBlank()) {
                throw invalidRequest("REFINE 模式必须提供辅助分析意见");
            }
            if (feedback.length() > MAX_FEEDBACK_LENGTH) {
                throw invalidRequest("辅助分析意见不能超过 " + MAX_FEEDBACK_LENGTH + " 个字符");
            }
        }
        return new ValidatedRetry(
                requestedMode,
                requestedMode == ResumeProfileAnalysisMode.REFINE ? feedback.trim() : null);
    }

    /**
     * 在事实解析提交异常后确认数据库任务终态。
     * 尚未形成事件的业务拒绝视为明确未执行，其他未知异常或失败写回异常返回 UNKNOWN 以阻止额度误结算。
     */
    private ResumeAiTaskOutcome confirmParseSubmissionFailure(
            Long resumeId,
            Long userId,
            ResumeParseRequestedEvent event,
            ResumeAiQuotaReservation reservation,
            RuntimeException submissionError) {
        if (event == null) {
            return submissionError instanceof BusinessException
                    ? ResumeAiTaskOutcome.FAILURE_CONFIRMED
                    : ResumeAiTaskOutcome.UNKNOWN;
        }
        try {
            return parseStateService.fail(
                    resumeId,
                    userId,
                    event.generation(),
                    reservation,
                    "SCHEDULING_FAILED",
                    "解析任务提交失败，请稍后重试");
        } catch (RuntimeException confirmationError) {
            log.warn(
                    "[ResumeTask] 提交失败后的解析终态无法确认: resumeId={}, errorType={}",
                    resumeId,
                    confirmationError.getClass().getSimpleName());
            return ResumeAiTaskOutcome.UNKNOWN;
        }
    }

    /**
     * 在辅助分析提交异常后确认数据库任务终态。
     * 已形成事件时按任务代次、事实 hash 和额度 token 写失败；无法确认时保留额度恢复凭据。
     */
    private ResumeAiTaskOutcome confirmAnalysisSubmissionFailure(
            Long resumeId,
            Long userId,
            ResumeProfileAnalysisRequestedEvent event,
            ResumeAiQuotaReservation reservation,
            RuntimeException submissionError) {
        if (event == null) {
            return submissionError instanceof BusinessException
                    ? ResumeAiTaskOutcome.FAILURE_CONFIRMED
                    : ResumeAiTaskOutcome.UNKNOWN;
        }
        try {
            return analysisStateService.fail(
                    resumeId,
                    userId,
                    event.taskGeneration(),
                    event.taskProfileHash(),
                    reservation,
                    "SCHEDULING_FAILED",
                    "分析任务提交失败，请稍后重试");
        } catch (RuntimeException confirmationError) {
            log.warn(
                    "[ResumeTask] 提交失败后的分析终态无法确认: resumeId={}, errorType={}",
                    resumeId,
                    confirmationError.getClass().getSimpleName());
            return ResumeAiTaskOutcome.UNKNOWN;
        }
    }

    /**
     * 释放尚未进入 Worker 的任务许可；释放异常只记录非敏感标识，随后仍继续数据库与额度补偿。
     */
    private void releaseLeaseQuietly(
            Long userId,
            Long resumeId,
            ResumeAiTaskLease lease,
            String taskType) {
        if (lease != null) {
            try {
                leaseRunner.releaseWithoutRun(userId, resumeId, lease);
            } catch (RuntimeException releaseError) {
                log.warn(
                        "[ResumeTask] 提交失败后的许可释放失败: resumeId={}, taskType={}, errorType={}",
                        resumeId,
                        taskType,
                        releaseError.getClass().getSimpleName());
            }
        }
    }

    /**
     * 按数据库确认结果处理提交阶段额度，并只在同日 Redis 转换成功或额度日期关闭时清理数据库凭据。
     */
    private void settleSubmissionQuota(
            Long userId,
            Long resumeId,
            ResumeAiQuotaReservation reservation,
            ResumeAiTaskOutcome outcome,
            Runnable clearPersistedReservation,
            String taskType) {
        try {
            if (!ResumeAiQuotaSettlement.settle(
                    quotaService,
                    userId,
                    reservation,
                    outcome,
                    clearPersistedReservation)) {
                log.warn(
                        "[ResumeTask] 提交失败后的额度仍待恢复: resumeId={}, taskType={}, outcome={}",
                        resumeId,
                        taskType,
                        outcome);
            }
        } catch (RuntimeException settlementError) {
            log.warn(
                    "[ResumeTask] 提交失败后的额度结算未完成: resumeId={}, taskType={}, outcome={}, errorType={}",
                    resumeId,
                    taskType,
                    outcome,
                    settlementError.getClass().getSimpleName());
        }
    }

    /** 将请求校验失败映射为简历模块稳定错误码，不暴露内部异常。 */
    private BusinessException invalidRequest(String message) {
        return new BusinessException(ResumeErrorCode.PROFILE_ANALYSIS_REQUEST_INVALID, message);
    }

    /**
     * 资源占用前完成校验的辅助分析请求。
     *
     * @param requestedMode 服务端解析后的公开模式，只会是 REGENERATE 或 REFINE
     * @param feedback REFINE 使用的去首尾空白意见，可能包含用户敏感内容；其他模式为 null
     */
    private record ValidatedRetry(
            ResumeProfileAnalysisMode requestedMode, String feedback) {
    }
}
