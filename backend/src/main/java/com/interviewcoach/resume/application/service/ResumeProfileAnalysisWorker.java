package com.interviewcoach.resume.application.service;

import com.interviewcoach.resume.application.event.ResumeProfileAnalysisRequestedEvent;
import com.interviewcoach.resume.application.event.ResumeAiQuotaReservation;
import com.interviewcoach.resume.domain.agent.ResumeProfileAnalysisAgent;
import com.interviewcoach.resume.domain.entity.ResumeProfileAnalysisMode;
import com.interviewcoach.resume.domain.model.ResumeProfileAnalysisData;
import com.interviewcoach.resume.infrastructure.redis.ResumeAiQuotaService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 在事务外调用模型生成画像分析，通过短事务写回，并按数据库确认的终态结算 quota。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ResumeProfileAnalysisWorker {

    private final ResumeProfileAnalysisStateService stateService;
    private final ResumeProfileAnalysisAgent analysisAgent;
    private final ResumeAiQuotaService quotaService;

    /**
     * 在短事务之间执行模型调用；任何异常都先确认数据库终态，无法确认时保留 quota 恢复凭据。
     */
    public void analyze(ResumeProfileAnalysisRequestedEvent event) {
        ResumeAiQuotaReservation quotaReservation = event.quotaReservation();
        ResumeAiTaskOutcome outcome = ResumeAiTaskOutcome.UNKNOWN;
        try {
            if (event.taskLease() == null) {
                throw new IllegalStateException("AI task lease is required before worker execution");
            }
            ResumeProfileAnalysisStateService.AnalysisInput input = stateService.start(
                    event.resumeId(),
                    event.userId(),
                    event.taskGeneration(),
                    event.taskProfileHash());
            if (input == null) {
                outcome = confirmFailure(
                        event,
                        quotaReservation,
                        "ANALYSIS_NOT_EXECUTABLE",
                        "画像分析任务状态已变化，请稍后重试");
            } else {
                quotaReservation = input.quotaReservation();
                ResumeProfileAnalysisData data = analysisAgent.analyze(
                        input.profileData(),
                        input.previousAnalysis(),
                        event.feedback(),
                        input.taskMode(),
                        () -> markModelCallStarted(event, input));
                boolean completed = event.taskLease().executeIfValid(() -> stateService.complete(
                        event.resumeId(),
                        event.userId(),
                        event.taskGeneration(),
                        event.taskProfileHash(),
                        data));
                if (!completed && !event.taskLease().isValid()) {
                    throw new IllegalStateException("AI task lease was lost before result persistence");
                }
                outcome = completed
                        ? ResumeAiTaskOutcome.SUCCESS_CONFIRMED
                        : confirmFailure(
                                event,
                                quotaReservation,
                                "ANALYSIS_RESULT_DISCARDED",
                                "画像分析结果已过期，请稍后重试");
            }
        } catch (Exception e) {
            outcome = confirmFailure(
                    event,
                    quotaReservation,
                    "ANALYSIS_FAILED",
                    "画像分析失败，请稍后重试");
            log.error(
                    "[ResumeProfileAnalysis] 分析执行异常: resumeId={}, outcome={}, errorType={}",
                    event.resumeId(), outcome, e.getClass().getSimpleName());
        }
        settleQuota(event, quotaReservation, outcome, "WORKER_COMPLETION");
    }

    private void markModelCallStarted(
            ResumeProfileAnalysisRequestedEvent event,
            ResumeProfileAnalysisStateService.AnalysisInput input) {
        boolean accepted;
        if (input.taskMode() == ResumeProfileAnalysisMode.INITIAL) {
            if (input.quotaReservation() != null) {
                throw new IllegalStateException("INITIAL task must not carry a quota reservation");
            }
            accepted = stateService.markInitialModelCallStarted(
                    event.resumeId(),
                    event.userId(),
                    event.taskGeneration(),
                    event.taskProfileHash());
        } else {
            if (input.quotaReservation() == null) {
                throw new IllegalStateException("Manual analysis task requires a quota reservation");
            }
            accepted = quotaService.markAttemptStarted(
                    event.userId(), input.quotaReservation());
        }
        if (!accepted) {
            throw new IllegalStateException("Model-call start transition was rejected");
        }
    }

    private ResumeAiTaskOutcome confirmFailure(
            ResumeProfileAnalysisRequestedEvent event,
            ResumeAiQuotaReservation quotaReservation,
            String errorCode,
            String errorMessage) {
        try {
            return stateService.fail(
                    event.resumeId(),
                    event.userId(),
                    event.taskGeneration(),
                    event.taskProfileHash(),
                    quotaReservation,
                    errorCode,
                    errorMessage);
        } catch (RuntimeException confirmationError) {
            log.error(
                    "[ResumeProfileAnalysis] 无法确认数据库任务终态: resumeId={}, generation={}, errorType={}",
                    event.resumeId(),
                    event.taskGeneration(),
                    confirmationError.getClass().getSimpleName());
            return ResumeAiTaskOutcome.UNKNOWN;
        }
    }

    private void settleQuota(
            ResumeProfileAnalysisRequestedEvent event,
            ResumeAiQuotaReservation quotaReservation,
            ResumeAiTaskOutcome outcome,
            String stage) {
        try {
            boolean settled = ResumeAiQuotaSettlement.settle(
                    quotaService,
                    event.userId(),
                    quotaReservation,
                    outcome,
                    () -> stateService.clearQuotaReservation(
                            event.resumeId(),
                            event.userId(),
                            event.taskGeneration(),
                            event.taskProfileHash(),
                            quotaReservation));
            if (!settled && quotaReservation != null) {
                log.warn(
                        "[ResumeProfileAnalysis] 额度结算待恢复: resumeId={}, generation={}, stage={}, outcome={}",
                        event.resumeId(),
                        event.taskGeneration(),
                        stage,
                        outcome);
            }
        } catch (RuntimeException settlementError) {
            log.warn(
                    "[ResumeProfileAnalysis] 额度结算失败并保留恢复凭据: "
                            + "resumeId={}, generation={}, stage={}, outcome={}, errorType={}",
                    event.resumeId(),
                    event.taskGeneration(),
                    stage,
                    outcome,
                    settlementError.getClass().getSimpleName());
        }
    }

    /**
     * 队列拒绝时先确认当前任务失败，再结算 quota；不改变正式画像和面试资格。
     */
    public void markSchedulingFailed(ResumeProfileAnalysisRequestedEvent event) {
        ResumeAiTaskOutcome outcome = confirmFailure(
                event,
                event.quotaReservation(),
                "SCHEDULING_FAILED",
                "分析任务调度失败，请稍后重试");
        settleQuota(event, event.quotaReservation(), outcome, "SCHEDULING_FAILED");
    }

    /**
     * 准入失败时确认当前分析代次失败并结算 quota，保留旧成功结果且不恢复面试可用性。
     */
    public void markAdmissionFailed(ResumeProfileAnalysisRequestedEvent event) {
        ResumeAiTaskOutcome outcome = confirmFailure(
                event,
                event.quotaReservation(),
                "ADMISSION_REJECTED",
                "分析任务并发已达上限或基础设施不可用，请稍后重试");
        settleQuota(event, event.quotaReservation(), outcome, "ADMISSION_REJECTED");
    }
}
