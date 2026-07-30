package com.interviewcoach.resume.application.service;

import com.interviewcoach.resume.application.event.ResumeAiQuotaReservation;
import com.interviewcoach.resume.domain.entity.Resume;
import com.interviewcoach.resume.domain.entity.ResumeParseStatus;
import com.interviewcoach.resume.domain.entity.ResumeProfileAnalysis;
import com.interviewcoach.resume.domain.entity.ResumeProfileAnalysisStatus;
import com.interviewcoach.resume.domain.repository.ResumeProfileAnalysisRepository;
import com.interviewcoach.resume.domain.repository.ResumeRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 单实例启动时失败上次进程遗留的内存任务，并按数据库终态恢复未完成的 quota 结算。
 */
@Service
@RequiredArgsConstructor
public class ResumeTaskRecoveryService {

    private static final List<ResumeParseStatus> INTERRUPTED_PARSE_STATUSES =
            List.of(ResumeParseStatus.PENDING, ResumeParseStatus.PARSING);
    private static final List<ResumeParseStatus> RECOVERABLE_PARSE_QUOTA_STATUSES =
            List.of(
                    ResumeParseStatus.PENDING,
                    ResumeParseStatus.PARSING,
                    ResumeParseStatus.PENDING_CONFIRM,
                    ResumeParseStatus.CONFIRMED,
                    ResumeParseStatus.PARSE_FAILED);
    private static final List<ResumeProfileAnalysisStatus> INTERRUPTED_ANALYSIS_STATUSES =
            List.of(ResumeProfileAnalysisStatus.PENDING, ResumeProfileAnalysisStatus.RUNNING);
    private static final List<ResumeProfileAnalysisStatus> RECOVERABLE_ANALYSIS_QUOTA_STATUSES =
            List.of(
                    ResumeProfileAnalysisStatus.PENDING,
                    ResumeProfileAnalysisStatus.RUNNING,
                    ResumeProfileAnalysisStatus.SUCCEEDED,
                    ResumeProfileAnalysisStatus.FAILED);

    private final ResumeRepository resumeRepository;
    private final ResumeProfileAnalysisRepository analysisRepository;

    /**
     * 批量失败上个进程遗留的内存任务，同时保留全部 quota token 供事务外按终态结算。
     */
    @Transactional
    public RecoveryResult failInterruptedTasks() {
        LocalDateTime now = LocalDateTime.now();
        List<ParseQuotaRecovery> parseQuotaRecoveries = resumeRepository
                .findByParseStatusInAndParseQuotaDateIsNotNullAndParseQuotaTokenIsNotNull(
                        RECOVERABLE_PARSE_QUOTA_STATUSES)
                .stream()
                .map(resume -> new ParseQuotaRecovery(
                        resume.getId(),
                        resume.getUserId(),
                        resume.getParseGeneration(),
                        new ResumeAiQuotaReservation(
                                resume.getParseQuotaDate(), resume.getParseQuotaToken()),
                        parseOutcome(resume.getParseStatus())))
                .toList();
        List<AnalysisQuotaRecovery> analysisQuotaRecoveries = analysisRepository
                .findByStatusInAndTaskQuotaDateIsNotNullAndTaskQuotaTokenIsNotNull(
                        RECOVERABLE_ANALYSIS_QUOTA_STATUSES)
                .stream()
                .map(analysis -> new AnalysisQuotaRecovery(
                        analysis.getResumeId(),
                        analysis.getUserId(),
                        analysis.getTaskGeneration(),
                        analysis.getTaskProfileHash(),
                        new ResumeAiQuotaReservation(
                                analysis.getTaskQuotaDate(), analysis.getTaskQuotaToken()),
                        analysisOutcome(analysis.getStatus())))
                .toList();
        int parses = resumeRepository.markInterruptedParsesAsFailed(
                INTERRUPTED_PARSE_STATUSES,
                ResumeParseStatus.PARSE_FAILED,
                "APPLICATION_RESTARTED",
                "应用重启导致解析中断，请手动重试",
                now);
        int analyses = analysisRepository.markInterruptedAsFailed(
                INTERRUPTED_ANALYSIS_STATUSES,
                ResumeProfileAnalysisStatus.FAILED,
                "APPLICATION_RESTARTED",
                "应用重启导致分析中断，请手动重试",
                now);
        return new RecoveryResult(
                parses, analyses, parseQuotaRecoveries, analysisQuotaRecoveries);
    }

    /**
     * Redis 已确认终态转换后，按归属、代次和 token 清除事实解析恢复元数据。
     */
    @Transactional
    public void clearRecoveredParseQuota(ParseQuotaRecovery recovery) {
        Resume resume = resumeRepository.findByIdAndUserIdForUpdate(
                        recovery.resumeId(), recovery.userId())
                .orElse(null);
        if (resume == null
                || !Objects.equals(resume.getParseGeneration(), recovery.generation())
                || !Objects.equals(resume.getParseQuotaDate(), recovery.reservation().quotaDate())
                || !Objects.equals(resume.getParseQuotaToken(), recovery.reservation().quotaToken())) {
            return;
        }
        resume.setParseQuotaDate(null);
        resume.setParseQuotaToken(null);
        resumeRepository.save(resume);
    }

    /**
     * Redis 已确认终态转换后，按归属、任务代次、事实 hash 和 token 清除分析恢复元数据。
     */
    @Transactional
    public void clearRecoveredAnalysisQuota(AnalysisQuotaRecovery recovery) {
        ResumeProfileAnalysis analysis = analysisRepository.findByResumeIdAndUserIdForUpdate(
                        recovery.resumeId(), recovery.userId())
                .orElse(null);
        if (analysis == null
                || !Objects.equals(analysis.getTaskGeneration(), recovery.taskGeneration())
                || !Objects.equals(analysis.getTaskProfileHash(), recovery.taskProfileHash())
                || !Objects.equals(analysis.getTaskQuotaDate(), recovery.reservation().quotaDate())
                || !Objects.equals(analysis.getTaskQuotaToken(), recovery.reservation().quotaToken())) {
            return;
        }
        analysis.setTaskQuotaDate(null);
        analysis.setTaskQuotaToken(null);
        analysisRepository.save(analysis);
    }

    public record RecoveryResult(
            int parseCount,
            int analysisCount,
            List<ParseQuotaRecovery> parseQuotaRecoveries,
            List<AnalysisQuotaRecovery> analysisQuotaRecoveries) {
    }

    public record ParseQuotaRecovery(
            Long resumeId,
            Long userId,
            Long generation,
            ResumeAiQuotaReservation reservation,
            ResumeAiTaskOutcome outcome) {

        public ParseQuotaRecovery(
                Long resumeId,
                Long userId,
                Long generation,
                ResumeAiQuotaReservation reservation) {
            this(resumeId, userId, generation, reservation, ResumeAiTaskOutcome.FAILURE_CONFIRMED);
        }
    }

    public record AnalysisQuotaRecovery(
            Long resumeId,
            Long userId,
            Long taskGeneration,
            String taskProfileHash,
            ResumeAiQuotaReservation reservation,
            ResumeAiTaskOutcome outcome) {

        public AnalysisQuotaRecovery(
                Long resumeId,
                Long userId,
                Long taskGeneration,
                String taskProfileHash,
                ResumeAiQuotaReservation reservation) {
            this(
                    resumeId,
                    userId,
                    taskGeneration,
                    taskProfileHash,
                    reservation,
                    ResumeAiTaskOutcome.FAILURE_CONFIRMED);
        }
    }

    private static ResumeAiTaskOutcome parseOutcome(ResumeParseStatus status) {
        return status == ResumeParseStatus.PENDING_CONFIRM || status == ResumeParseStatus.CONFIRMED
                ? ResumeAiTaskOutcome.SUCCESS_CONFIRMED
                : ResumeAiTaskOutcome.FAILURE_CONFIRMED;
    }

    private static ResumeAiTaskOutcome analysisOutcome(ResumeProfileAnalysisStatus status) {
        return status == ResumeProfileAnalysisStatus.SUCCEEDED
                ? ResumeAiTaskOutcome.SUCCESS_CONFIRMED
                : ResumeAiTaskOutcome.FAILURE_CONFIRMED;
    }
}
