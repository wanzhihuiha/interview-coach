package com.interviewcoach.resume.application.service;

import com.interviewcoach.common.exception.BusinessException;
import com.interviewcoach.resume.application.event.ResumeAiQuotaReservation;
import com.interviewcoach.resume.application.event.ResumeParseRequestedEvent;
import com.interviewcoach.resume.domain.entity.Resume;
import com.interviewcoach.resume.domain.entity.ResumeParseStatus;
import com.interviewcoach.resume.domain.model.UserProfileData;
import com.interviewcoach.resume.domain.repository.ResumeRepository;
import java.time.LocalDateTime;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 使用短事务推进简历解析状态，generation 保证过期任务不能覆盖新草稿。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ResumeParseStateService {

    private final ResumeRepository resumeRepository;
    private final ResumeProfileSupport profileSupport;

    /**
     * 在取得 Redis 资源前只读校验手动重解析资格；真正登记时仍会在锁内复查。
     */
    @Transactional(readOnly = true)
    public void validateManualReparse(Long resumeId, Long userId) {
        Resume resume = resumeRepository.findByIdAndUserId(resumeId, userId)
                .orElseThrow(() -> new BusinessException(ResumeErrorCode.RESUME_NOT_FOUND, "简历不存在"));
        validateCanReparse(resume);
    }

    /**
     * 取得并发许可和额度后，在短事务内登记新的手动事实解析代次及恢复元数据。
     */
    @Transactional
    public PreparedParse prepareManualReparse(
            Long resumeId, Long userId, ResumeAiQuotaReservation quotaReservation) {
        Resume resume = resumeRepository.findByIdAndUserIdForUpdate(resumeId, userId)
                .orElseThrow(() -> new BusinessException(ResumeErrorCode.RESUME_NOT_FOUND, "简历不存在"));
        validateCanReparse(resume);
        long nextGeneration = resume.getParseGeneration() == null
                ? 1L : Math.addExact(resume.getParseGeneration(), 1L);
        resume.setParseGeneration(nextGeneration);
        resume.setParseStatus(ResumeParseStatus.PENDING);
        resume.setParseStartedAt(null);
        resume.setParseErrorCode(null);
        resume.setParseErrorMessage(null);
        resume.setParseQuotaDate(quotaReservation.quotaDate());
        resume.setParseQuotaToken(quotaReservation.quotaToken());
        resumeRepository.save(resume);
        return new PreparedParse(
                resume,
                new ResumeParseRequestedEvent(
                        resumeId, userId, nextGeneration, true, null, quotaReservation));
    }

    /**
     * 锁定简历并认领当前代次的 PENDING 任务；旧代次或其他状态返回 null。
     */
    @Transactional
    public ParseInput start(Long resumeId, Long userId, Long generation) {
        Resume resume = resumeRepository.findByIdAndUserIdForUpdate(resumeId, userId).orElse(null);
        if (resume == null || resume.getParseStatus() != ResumeParseStatus.PENDING
                || !Objects.equals(resume.getParseGeneration(), generation)) {
            log.info("[ResumeParse] 跳过过期或不可执行任务: resumeId={}, generation={}", resumeId, generation);
            return null;
        }
        resume.setParseStatus(ResumeParseStatus.PARSING);
        resume.setParseStartedAt(LocalDateTime.now());
        resume.setParseErrorCode(null);
        resume.setParseErrorMessage(null);
        resumeRepository.save(resume);
        ResumeAiQuotaReservation quotaReservation = resume.getParseQuotaDate() == null
                || resume.getParseQuotaToken() == null
                ? null
                : new ResumeAiQuotaReservation(
                        resume.getParseQuotaDate(), resume.getParseQuotaToken());
        return new ParseInput(resume.getFilePath(), resume.getFileType(), quotaReservation);
    }

    /**
     * 只把当前代次的 PARSING 结果写成草稿；quota token 保留到 Redis 成功结算后再清理。
     */
    @Transactional
    public boolean complete(
            Long resumeId, Long userId, Long generation, UserProfileData profileData) {
        Resume resume = resumeRepository.findByIdAndUserIdForUpdate(resumeId, userId).orElse(null);
        if (resume == null || resume.getParseStatus() != ResumeParseStatus.PARSING
                || !Objects.equals(resume.getParseGeneration(), generation)) {
            log.warn("[ResumeParse] 丢弃过期解析结果: resumeId={}, generation={}", resumeId, generation);
            return false;
        }
        profileSupport.saveDraft(resumeId, userId, generation, profileData);
        resume.setJobCategory(profileSupport.inferJobCategory(profileData));
        resume.setParseStatus(ResumeParseStatus.PENDING_CONFIRM);
        resume.setParseStartedAt(null);
        resume.setParseErrorCode(null);
        resume.setParseErrorMessage(null);
        resumeRepository.save(resume);
        return true;
    }

    /**
     * 兼容旧调用：只失败当前代次仍在等待或执行中的任务，并保留 quota 恢复凭据。
     */
    @Transactional
    public void fail(Long resumeId, Long userId, Long generation, String errorCode, String errorMessage) {
        Resume resume = resumeRepository.findByIdAndUserIdForUpdate(resumeId, userId).orElse(null);
        if (resume == null || !Objects.equals(resume.getParseGeneration(), generation)) {
            return;
        }
        markFailedIfRunning(resume, errorCode, errorMessage);
    }

    /**
     * 按归属、代次和 quota token 确认当前任务终态；无法确认时不允许调用方释放成功预留。
     */
    @Transactional
    public ResumeAiTaskOutcome fail(
            Long resumeId,
            Long userId,
            Long generation,
            ResumeAiQuotaReservation expectedReservation,
            String errorCode,
            String errorMessage) {
        Resume resume = resumeRepository.findByIdAndUserIdForUpdate(resumeId, userId).orElse(null);
        if (resume == null
                || !Objects.equals(resume.getParseGeneration(), generation)
                || !quotaMatches(resume, expectedReservation)) {
            return ResumeAiTaskOutcome.UNKNOWN;
        }
        if (resume.getParseStatus() == ResumeParseStatus.PENDING_CONFIRM
                || resume.getParseStatus() == ResumeParseStatus.CONFIRMED) {
            return ResumeAiTaskOutcome.SUCCESS_CONFIRMED;
        }
        if (resume.getParseStatus() == ResumeParseStatus.PARSE_FAILED) {
            return ResumeAiTaskOutcome.FAILURE_CONFIRMED;
        }
        if (resume.getParseStatus() == ResumeParseStatus.PENDING
                || resume.getParseStatus() == ResumeParseStatus.PARSING) {
            markFailedIfRunning(resume, errorCode, errorMessage);
            return ResumeAiTaskOutcome.FAILURE_CONFIRMED;
        }
        return ResumeAiTaskOutcome.UNKNOWN;
    }

    /**
     * Redis 已确认结算后，按归属、代次、日期和 token 幂等清理数据库恢复凭据。
     */
    @Transactional
    public void clearQuotaReservation(
            Long resumeId,
            Long userId,
            Long generation,
            ResumeAiQuotaReservation expectedReservation) {
        if (expectedReservation == null) {
            return;
        }
        Resume resume = resumeRepository.findByIdAndUserIdForUpdate(resumeId, userId).orElse(null);
        if (resume == null
                || !Objects.equals(resume.getParseGeneration(), generation)
                || !Objects.equals(resume.getParseQuotaDate(), expectedReservation.quotaDate())
                || !Objects.equals(resume.getParseQuotaToken(), expectedReservation.quotaToken())) {
            return;
        }
        resume.setParseQuotaDate(null);
        resume.setParseQuotaToken(null);
        resumeRepository.save(resume);
    }

    private void validateCanReparse(Resume resume) {
        if (resume.isLocked()) {
            throw new BusinessException(ResumeErrorCode.RESUME_LOCKED, "简历已锁定，不可重新解析");
        }
        if (resume.getParseStatus() == ResumeParseStatus.PENDING
                || resume.getParseStatus() == ResumeParseStatus.PARSING) {
            throw new BusinessException(
                    ResumeErrorCode.RESUME_PARSING_IN_PROGRESS, "简历正在解析中，请稍后再试");
        }
        if (resume.getParseQuotaDate() != null || resume.getParseQuotaToken() != null) {
            throw new BusinessException(
                    ResumeErrorCode.RESUME_TASK_INFRASTRUCTURE_UNAVAILABLE,
                    "上一解析任务额度尚未结算，请稍后重试");
        }
    }

    private void markFailedIfRunning(Resume resume, String errorCode, String errorMessage) {
        if (resume.getParseStatus() != ResumeParseStatus.PENDING
                && resume.getParseStatus() != ResumeParseStatus.PARSING) {
            return;
        }
        resume.setParseStatus(ResumeParseStatus.PARSE_FAILED);
        resume.setParseStartedAt(null);
        resume.setParseErrorCode(errorCode);
        resume.setParseErrorMessage(errorMessage);
        resumeRepository.save(resume);
    }

    private boolean quotaMatches(Resume resume, ResumeAiQuotaReservation expectedReservation) {
        if (expectedReservation == null) {
            return resume.getParseQuotaDate() == null && resume.getParseQuotaToken() == null;
        }
        return Objects.equals(resume.getParseQuotaDate(), expectedReservation.quotaDate())
                && Objects.equals(resume.getParseQuotaToken(), expectedReservation.quotaToken());
    }

    public record ParseInput(
            String filePath,
            String fileType,
            ResumeAiQuotaReservation quotaReservation) {
    }

    public record PreparedParse(Resume resume, ResumeParseRequestedEvent event) {
    }
}
