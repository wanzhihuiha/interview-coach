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
 * 消费携带任务许可的辅助分析事件，在数据库短事务之间调用模型生成待验证选题线索。
 * 状态服务负责认领和写回，Worker 只在任务许可有效时持久化结果，并按数据库确认终态结算 Redis 额度。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ResumeProfileAnalysisWorker {

    /** 认领任务、标记首次模型调用、写回结果及清理额度恢复凭据的状态服务。 */
    private final ResumeProfileAnalysisStateService stateService;
    /** 基于已确认事实、旧分析和可选反馈调用 LLM 的辅助分析 Agent。 */
    private final ResumeProfileAnalysisAgent analysisAgent;
    /** 标记手动任务模型调用开始并结算每日 AI 额度的 Redis 服务。 */
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
            // 锁定并认领当前任务，读取正式事实、旧结果、实际模式和数据库保存的额度凭据。
            ResumeProfileAnalysisStateService.AnalysisInput input = stateService.start(
                    event.resumeId(),
                    event.userId(),
                    event.taskGeneration(),
                    event.taskProfileHash());
            if (input == null) {
                // 任务或正式画像版本已经变化，先确认数据库终态再决定额度能否结算。
                outcome = confirmFailure(
                        event,
                        quotaReservation,
                        "ANALYSIS_NOT_EXECUTABLE",
                        "画像分析任务状态已变化，请稍后重试");
            } else {
                quotaReservation = input.quotaReservation();
                // Agent 在真正发送模型请求前执行回调，消费免费 INITIAL 资格或标记手动额度已开始。
                ResumeProfileAnalysisData data = analysisAgent.analyze(
                        input.profileData(),
                        input.previousAnalysis(),
                        event.feedback(),
                        input.taskMode(),
                        () -> markModelCallStarted(event, input));
                // 许可仍有效时才写回；许可丢失会阻止旧执行者覆盖当前任务。
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
        // 所有成功和失败路径最终都按数据库可确认结果结算，UNKNOWN 会保留恢复凭据。
        settleQuota(event, quotaReservation, outcome, "WORKER_COMPLETION");
    }

    /**
     * 在模型调用紧前记录不可回退的调用开始事实。
     * 免费 INITIAL 写数据库首次标记，手动模式写 Redis 额度状态；任何不匹配都阻止外部调用。
     */
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

    /** 按归属、代次、事实 hash 和额度凭据写失败终态；无法确认时返回 UNKNOWN。 */
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

    /** 同日 Redis 转换成功或额度日期关闭后才按任务凭据清除数据库字段，失败时留给启动恢复处理。 */
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
        // 事件已登记但执行器拒绝时，将当前任务落为失败后再结算其额度。
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
        // 后台准入未通过时任务不会调用模型，仍按数据库确认结果处理可能存在的预留。
        ResumeAiTaskOutcome outcome = confirmFailure(
                event,
                event.quotaReservation(),
                "ADMISSION_REJECTED",
                "分析任务并发已达上限或基础设施不可用，请稍后重试");
        settleQuota(event, event.quotaReservation(), outcome, "ADMISSION_REJECTED");
    }
}
