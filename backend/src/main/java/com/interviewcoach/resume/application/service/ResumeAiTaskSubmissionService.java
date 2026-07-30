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
 * 编排手动事实解析、手动辅助分析和确认后的可选 INITIAL，统一管理 Redis 资源与任务交接补偿。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ResumeAiTaskSubmissionService {

    private static final int MAX_FEEDBACK_LENGTH = 1000;

    private final ResumeParseStateService parseStateService;
    private final ResumeProfileAnalysisStateService analysisStateService;
    private final ResumeAiTaskAdmissionService admissionService;
    private final ResumeAiTaskLeaseRunner leaseRunner;
    private final ResumeAiQuotaService quotaService;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * 手动事实重解析先取得许可和额度再登记代次；异常时只结算数据库能够明确确认的结果。
     */
    public Resume submitManualReparse(Long userId, Long resumeId) {
        parseStateService.validateManualReparse(resumeId, userId);
        ResumeAiTaskLease lease = admissionService.acquire(userId, resumeId);
        ResumeAiQuotaReservation reservation = null;
        ResumeParseRequestedEvent event = null;
        try {
            reservation = quotaService.reserve(userId);
            PreparedParse prepared = parseStateService.prepareManualReparse(
                    resumeId, userId, reservation);
            event = prepared.event().withTaskLease(lease);
            eventPublisher.publishEvent(event);
            return prepared.resume();
        } catch (RuntimeException e) {
            ResumeParseRequestedEvent failedEvent = event;
            ResumeAiQuotaReservation failedReservation = reservation;
            ResumeAiTaskOutcome outcome = confirmParseSubmissionFailure(
                    resumeId, userId, failedEvent, failedReservation, e);
            releaseLeaseQuietly(userId, resumeId, lease, "manual-parse");
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
        ValidatedRetry validated = validateRetryRequest(request);
        RetryPlan plan = analysisStateService.previewRetry(
                resumeId, userId, validated.requestedMode());
        ResumeAiTaskLease lease = admissionService.acquire(userId, resumeId);
        ResumeAiQuotaReservation reservation = null;
        ResumeProfileAnalysisRequestedEvent event = null;
        try {
            if (plan.quotaRequired()) {
                reservation = quotaService.reserve(userId);
            }
            event = analysisStateService.prepareRetry(
                    resumeId,
                    userId,
                    validated.requestedMode(),
                    plan.taskMode(),
                    reservation,
                    validated.feedback());
            event = event.withTaskLease(lease);
            eventPublisher.publishEvent(event);
        } catch (RuntimeException e) {
            ResumeProfileAnalysisRequestedEvent failedEvent = event;
            ResumeAiQuotaReservation failedReservation = reservation;
            ResumeAiTaskOutcome outcome = confirmAnalysisSubmissionFailure(
                    resumeId, userId, failedEvent, failedReservation, e);
            releaseLeaseQuietly(userId, resumeId, lease, "manual-analysis");
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
            lease = admissionService.acquire(userId, confirmed.resumeId());
            ResumeAiTaskLease acquiredLease = lease;
            event = analysisStateService.prepareOptionalInitial(
                            confirmed.resumeId(), userId, confirmed.profileHash())
                    .map(item -> item.withTaskLease(acquiredLease))
                    .orElse(null);
            if (event == null) {
                releaseLeaseQuietly(userId, confirmed.resumeId(), lease, "optional-initial");
                return;
            }
            eventPublisher.publishEvent(event);
        } catch (RuntimeException e) {
            if (event != null) {
                try {
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

    private BusinessException invalidRequest(String message) {
        return new BusinessException(ResumeErrorCode.PROFILE_ANALYSIS_REQUEST_INVALID, message);
    }

    private record ValidatedRetry(
            ResumeProfileAnalysisMode requestedMode, String feedback) {
    }
}
