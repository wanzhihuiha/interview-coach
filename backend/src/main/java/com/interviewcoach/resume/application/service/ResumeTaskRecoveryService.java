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
 * 由当前应用进程的启动恢复入口调用，将数据库中遗留的执行中任务标为失败，
 * 并导出仍有额度凭据的任务，供事务外按终态推进同日 Redis 状态或清理已关闭日期的凭据。
 * 源码不能证明部署为单实例，也不能证明多个进程是否共享同一 Redis Key 空间；本服务不作该拓扑保证。
 */
@Service
@RequiredArgsConstructor
public class ResumeTaskRecoveryService {

    /** 当前进程启动时会批量标为 PARSE_FAILED 的事实解析中间状态。 */
    private static final List<ResumeParseStatus> INTERRUPTED_PARSE_STATUSES =
            List.of(ResumeParseStatus.PENDING, ResumeParseStatus.PARSING);
    /** 可携带事实解析额度恢复凭据的全部当前终态和中间状态。 */
    private static final List<ResumeParseStatus> RECOVERABLE_PARSE_QUOTA_STATUSES =
            List.of(
                    ResumeParseStatus.PENDING,
                    ResumeParseStatus.PARSING,
                    ResumeParseStatus.PENDING_CONFIRM,
                    ResumeParseStatus.CONFIRMED,
                    ResumeParseStatus.PARSE_FAILED);
    /** 当前进程启动时会批量标为 FAILED 的辅助分析中间状态。 */
    private static final List<ResumeProfileAnalysisStatus> INTERRUPTED_ANALYSIS_STATUSES =
            List.of(ResumeProfileAnalysisStatus.PENDING, ResumeProfileAnalysisStatus.RUNNING);
    /** 可携带辅助分析额度恢复凭据的全部当前终态和中间状态。 */
    private static final List<ResumeProfileAnalysisStatus> RECOVERABLE_ANALYSIS_QUOTA_STATUSES =
            List.of(
                    ResumeProfileAnalysisStatus.PENDING,
                    ResumeProfileAnalysisStatus.RUNNING,
                    ResumeProfileAnalysisStatus.SUCCEEDED,
                    ResumeProfileAnalysisStatus.FAILED);

    /** 查询额度凭据并批量失败遗留事实解析任务的简历仓储。 */
    private final ResumeRepository resumeRepository;
    /** 查询额度凭据并批量失败遗留辅助分析任务的仓储。 */
    private final ResumeProfileAnalysisRepository analysisRepository;

    /**
     * 在当前进程启动恢复时先采集所有带完整额度凭据的任务，再批量失败 PENDING/RUNNING 状态。
     * 查询和批量更新处于同一事务；返回项保留更新前终态对应的额度结果，token 不在本事务中清理。
     */
    @Transactional
    public RecoveryResult failInterruptedTasks() {
        LocalDateTime now = LocalDateTime.now();
        // 先按可恢复状态读取事实解析凭据，并把成功终态与失败/中间状态映射为额度结算结果。
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
        // 同样采集辅助分析凭据；任务 hash 和代次用于后续清理时防止误删新任务 token。
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
        // 批量更新会绕过实体回调，只修改仓储查询限定的事实解析中间状态并记录统一重启错误。
        int parses = resumeRepository.markInterruptedParsesAsFailed(
                INTERRUPTED_PARSE_STATUSES,
                ResumeParseStatus.PARSE_FAILED,
                "APPLICATION_RESTARTED",
                "应用重启导致解析中断，请手动重试",
                now);
        // 辅助分析批量失败保留旧成功 JSON，但最新任务变为 FAILED 且不可用于面试。
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
     * 同日 Redis 终态转换成功或额度日期已关闭后，按归属、代次和 token 清除事实解析恢复元数据。
     */
    @Transactional
    public void clearRecoveredParseQuota(ParseQuotaRecovery recovery) {
        // 统一结算器允许清理后按用户归属锁定简历，并精确匹配代次、日期和 token。
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
        // 仅删除恢复凭据；批量失败或原成功终态保持不变。
        resumeRepository.save(resume);
    }

    /**
     * 同日 Redis 终态转换成功或额度日期已关闭后，按归属、任务代次、事实 hash 和 token 清除分析恢复元数据。
     */
    @Transactional
    public void clearRecoveredAnalysisQuota(AnalysisQuotaRecovery recovery) {
        // 按用户归属锁定分析单行，代次、事实 hash、日期和 token 任一变化都跳过清理。
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
        // 仅清理凭据，不覆盖最新任务状态或保留的成功分析结果。
        analysisRepository.save(analysis);
    }

    /**
     * 当前进程启动恢复事务的输出，交给事务外恢复入口逐项结算 Redis。
     *
     * @param parseCount 本事务批量标为失败的事实解析记录数
     * @param analysisCount 本事务批量标为失败的辅助分析记录数
     * @param parseQuotaRecoveries 含完整额度凭据的事实解析恢复项，包含原数据库终态推导的结算结果
     * @param analysisQuotaRecoveries 含完整额度凭据的辅助分析恢复项，包含原数据库终态推导的结算结果
     */
    public record RecoveryResult(
            int parseCount,
            int analysisCount,
            List<ParseQuotaRecovery> parseQuotaRecoveries,
            List<AnalysisQuotaRecovery> analysisQuotaRecoveries) {
    }

    /**
     * 单个事实解析额度恢复项，用于 Redis 结算后精确清理同一数据库凭据。
     *
     * @param resumeId 待恢复任务所属简历标识
     * @param userId 额度 Key 和简历归属对应的用户标识
     * @param generation 采集时的解析代次
     * @param reservation 采集时数据库保存的额度日期与 token
     * @param outcome 采集时数据库解析状态对应的已确认成功或失败结果
     */
    public record ParseQuotaRecovery(
            Long resumeId,
            Long userId,
            Long generation,
            ResumeAiQuotaReservation reservation,
            ResumeAiTaskOutcome outcome) {

        /** 兼容构造入口，未显式提供结果时按失败终态恢复。 */
        public ParseQuotaRecovery(
                Long resumeId,
                Long userId,
                Long generation,
                ResumeAiQuotaReservation reservation) {
            this(resumeId, userId, generation, reservation, ResumeAiTaskOutcome.FAILURE_CONFIRMED);
        }
    }

    /**
     * 单个辅助分析额度恢复项，用任务代次与事实 hash 隔离后续新任务。
     *
     * @param resumeId 待恢复任务所属简历标识
     * @param userId 额度 Key 和分析归属对应的用户标识
     * @param taskGeneration 采集时的辅助分析任务代次
     * @param taskProfileHash 采集时任务绑定的正式事实 hash
     * @param reservation 采集时数据库保存的额度日期与 token
     * @param outcome 采集时数据库分析状态对应的已确认成功或失败结果
     */
    public record AnalysisQuotaRecovery(
            Long resumeId,
            Long userId,
            Long taskGeneration,
            String taskProfileHash,
            ResumeAiQuotaReservation reservation,
            ResumeAiTaskOutcome outcome) {

        /** 兼容构造入口，未显式提供结果时按失败终态恢复。 */
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

    /** 将待确认或已确认事实视为成功，其余可恢复状态视为失败。 */
    private static ResumeAiTaskOutcome parseOutcome(ResumeParseStatus status) {
        return status == ResumeParseStatus.PENDING_CONFIRM || status == ResumeParseStatus.CONFIRMED
                ? ResumeAiTaskOutcome.SUCCESS_CONFIRMED
                : ResumeAiTaskOutcome.FAILURE_CONFIRMED;
    }

    /** 将 SUCCEEDED 分析视为成功，其余可恢复状态视为失败。 */
    private static ResumeAiTaskOutcome analysisOutcome(ResumeProfileAnalysisStatus status) {
        return status == ResumeProfileAnalysisStatus.SUCCEEDED
                ? ResumeAiTaskOutcome.SUCCESS_CONFIRMED
                : ResumeAiTaskOutcome.FAILURE_CONFIRMED;
    }
}
