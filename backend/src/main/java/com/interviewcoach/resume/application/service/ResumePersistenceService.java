package com.interviewcoach.resume.application.service;

import com.interviewcoach.common.exception.BusinessException;
import com.interviewcoach.resume.domain.entity.Resume;
import com.interviewcoach.resume.domain.entity.ResumeParseStatus;
import com.interviewcoach.resume.domain.entity.ResumeProfileAnalysis;
import com.interviewcoach.resume.domain.entity.ResumeProfileAnalysisStatus;
import com.interviewcoach.resume.domain.model.UserProfileData;
import com.interviewcoach.resume.domain.repository.ResumeProfileAnalysisRepository;
import com.interviewcoach.resume.domain.repository.ResumeProfileDraftRepository;
import com.interviewcoach.resume.domain.repository.ResumeProfileRepository;
import com.interviewcoach.resume.domain.repository.ResumeRepository;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 为上传和删除提供不包含文件、Redis 或事件操作的数据库短事务。
 */
@Service
@RequiredArgsConstructor
public class ResumePersistenceService {

    private final ResumeRepository resumeRepository;
    private final ResumeProfileRepository profileRepository;
    private final ResumeProfileDraftRepository draftRepository;
    private final ResumeProfileAnalysisRepository analysisRepository;
    private final ResumeProfileSupport profileSupport;

    @Transactional
    public Resume createPending(
            Long userId, String resumeName, String filePath, String fileType, long fileSize) {
        return createPending(new Resume(), userId, resumeName, filePath, fileType, fileSize);
    }

    /**
     * 保存调用方预先持有的实体，使事务提交确认异常时仍可凭已分配 ID 执行补偿确认。
     */
    @Transactional
    public Resume createPending(
            Resume resume,
            Long userId,
            String resumeName,
            String filePath,
            String fileType,
            long fileSize) {
        resume.setUserId(userId);
        resume.setResumeName(resumeName);
        resume.setFilePath(filePath);
        resume.setFileType(fileType.toUpperCase());
        resume.setFileSize(fileSize);
        resume.setParseStatus(ResumeParseStatus.PENDING);
        resume.setParseGeneration(1L);
        return resumeRepository.saveAndFlush(resume);
    }

    /**
     * 独立提交正式事实并同步停用与新事实不匹配的旧分析；可选 INITIAL 在本事务提交后处理。
     */
    @Transactional
    public ConfirmedResume confirmProfile(
            Long userId,
            Long resumeId,
            Long requestedGeneration,
            UserProfileData profileData) {
        Resume resume = resumeRepository.findByIdAndUserIdForUpdate(resumeId, userId)
                .orElseThrow(() -> new BusinessException(ResumeErrorCode.RESUME_NOT_FOUND, "简历不存在"));
        if (resume.getParseStatus() != ResumeParseStatus.PENDING_CONFIRM) {
            throw new BusinessException(ResumeErrorCode.RESUME_STATUS_INVALID, "当前状态不允许确认");
        }
        if (requestedGeneration != null
                && !Objects.equals(requestedGeneration, resume.getParseGeneration())) {
            throw new BusinessException(ResumeErrorCode.PROFILE_DRAFT_STALE, "画像草稿已过期，请刷新后重试");
        }

        ResumeProfileSupport.ConfirmedProfile confirmed = profileSupport.confirmDraft(
                resumeId, userId, resume.getParseGeneration(), profileData);
        resume.setParseStatus(ResumeParseStatus.CONFIRMED);
        resume.setJobCategory(profileSupport.inferJobCategory(confirmed.data()));
        resume.setParseErrorCode(null);
        resume.setParseErrorMessage(null);
        resumeRepository.save(resume);

        ResumeProfileAnalysis analysis = analysisRepository
                .findByResumeIdAndUserIdForUpdate(resumeId, userId)
                .orElse(null);
        boolean taskRunning = analysis != null
                && (analysis.getStatus() == ResumeProfileAnalysisStatus.PENDING
                || analysis.getStatus() == ResumeProfileAnalysisStatus.RUNNING);
        if (analysis != null
                && !Objects.equals(analysis.getSourceProfileHash(), confirmed.profileHash())) {
            analysis.setUsableForInterview(false);
            analysisRepository.save(analysis);
        }
        boolean initialEligible = !taskRunning
                && (analysis == null || !analysis.isInitialModelCallStarted());
        return new ConfirmedResume(resumeId, confirmed.profileHash(), initialEligible);
    }

    /**
     * 删除用户拥有的完整简历记录并返回待删除文件路径；文件删除由事务提交后执行。
     */
    @Transactional
    public String deleteOwned(Long userId, Long resumeId) {
        Resume resume = resumeRepository.findByIdAndUserIdForUpdate(resumeId, userId)
                .orElseThrow(() -> new BusinessException(ResumeErrorCode.RESUME_NOT_FOUND, "简历不存在"));
        if (resume.isLocked()) {
            throw new BusinessException(ResumeErrorCode.RESUME_LOCKED_FOR_DELETE, "简历已锁定，不可删除");
        }
        deleteRelations(resumeId, userId);
        resumeRepository.delete(resume);
        resumeRepository.flush();
        return resume.getFilePath();
    }

    /**
     * 上传编排失败时只删除本次刚创建且仍归属于该用户的记录，不依赖文件事务回滚。
     */
    @Transactional
    public void deleteCreatedForCompensation(Long userId, Long resumeId) {
        Resume resume = resumeRepository.findByIdAndUserIdForUpdate(resumeId, userId).orElse(null);
        if (resume == null) {
            return;
        }
        deleteRelations(resumeId, userId);
        resumeRepository.delete(resume);
        resumeRepository.flush();
    }

    private void deleteRelations(Long resumeId, Long userId) {
        analysisRepository.findByResumeIdAndUserId(resumeId, userId).ifPresent(analysisRepository::delete);
        draftRepository.findByResumeIdAndUserId(resumeId, userId).ifPresent(draftRepository::delete);
        profileRepository.findByResumeIdAndUserId(resumeId, userId).ifPresent(profileRepository::delete);
    }

    /**
     * 正式事实事务提交后的非敏感结果，用于判断是否尝试可选 INITIAL。
     */
    public record ConfirmedResume(Long resumeId, String profileHash, boolean initialEligible) {
    }
}
