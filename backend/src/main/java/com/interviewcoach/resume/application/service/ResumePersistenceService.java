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
 * 为上传、正式画像确认和删除编排提供独立的数据库短事务。
 * 本服务只维护简历及其画像关系，不在事务中读写文件、Redis 或发布事件；
 * 文件补偿和可选辅助分析由事务提交后的上层服务继续处理。
 */
@Service
@RequiredArgsConstructor
public class ResumePersistenceService {

    /** 保存、归属锁定和删除简历主记录的仓储。 */
    private final ResumeRepository resumeRepository;
    /** 查询和删除用户已确认事实画像的仓储。 */
    private final ResumeProfileRepository profileRepository;
    /** 查询和删除当前解析草稿的仓储。 */
    private final ResumeProfileDraftRepository draftRepository;
    /** 查询、更新和删除辅助分析单行结果与任务状态的仓储。 */
    private final ResumeProfileAnalysisRepository analysisRepository;
    /** 将草稿规范化、校验并转为带 hash 的正式画像的领域组件。 */
    private final ResumeProfileSupport profileSupport;

    /**
     * 为普通上传创建一条 PENDING 简历记录，并委托可接收预分配实体的重载完成实际持久化。
     */
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
        // saveAndFlush 在返回前触发主记录写入，调用方据已分配 ID 协调许可和失败补偿。
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
        // 按用户归属锁定简历，避免确认、重解析和删除并发修改同一状态。
        Resume resume = resumeRepository.findByIdAndUserIdForUpdate(resumeId, userId)
                .orElseThrow(() -> new BusinessException(ResumeErrorCode.RESUME_NOT_FOUND, "简历不存在"));
        if (resume.getParseStatus() != ResumeParseStatus.PENDING_CONFIRM) {
            throw new BusinessException(ResumeErrorCode.RESUME_STATUS_INVALID, "当前状态不允许确认");
        }
        if (requestedGeneration != null
                && !Objects.equals(requestedGeneration, resume.getParseGeneration())) {
            throw new BusinessException(ResumeErrorCode.PROFILE_DRAFT_STALE, "画像草稿已过期，请刷新后重试");
        }

        // 规范化并校验用户提交事实，写入正式画像、计算 hash，同时删除已消费草稿。
        ResumeProfileSupport.ConfirmedProfile confirmed = profileSupport.confirmDraft(
                resumeId, userId, resume.getParseGeneration(), profileData);
        resume.setParseStatus(ResumeParseStatus.CONFIRMED);
        resume.setJobCategory(profileSupport.inferJobCategory(confirmed.data()));
        resume.setParseErrorCode(null);
        resume.setParseErrorMessage(null);
        // 简历状态与正式画像位于同一本地事务，任一步异常都会一起回滚。
        resumeRepository.save(resume);

        // 锁定同一用户的辅助分析单行，区分保留成功结果和当前任务状态。
        ResumeProfileAnalysis analysis = analysisRepository
                .findByResumeIdAndUserIdForUpdate(resumeId, userId)
                .orElse(null);
        boolean taskRunning = analysis != null
                && (analysis.getStatus() == ResumeProfileAnalysisStatus.PENDING
                || analysis.getStatus() == ResumeProfileAnalysisStatus.RUNNING);
        if (analysis != null
                && !Objects.equals(analysis.getSourceProfileHash(), confirmed.profileHash())) {
            // 新事实与旧结果 hash 不一致时仅撤销面试可用性，不删除旧结果或覆盖当前任务。
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
        // 最终删除仍按用户归属锁定；其他用户的 ID 与不存在资源使用同一错误。
        Resume resume = resumeRepository.findByIdAndUserIdForUpdate(resumeId, userId)
                .orElseThrow(() -> new BusinessException(ResumeErrorCode.RESUME_NOT_FOUND, "简历不存在"));
        if (resume.isLocked()) {
            throw new BusinessException(ResumeErrorCode.RESUME_LOCKED_FOR_DELETE, "简历已锁定，不可删除");
        }
        // 先删除分析、草稿和正式画像，再删除简历主记录，保持外键关系的数据库顺序。
        deleteRelations(resumeId, userId);
        resumeRepository.delete(resume);
        resumeRepository.flush();
        // 只在数据库删除已提交后由上层取得该路径并删除物理文件。
        return resume.getFilePath();
    }

    /**
     * 上传编排失败时只删除本次刚创建且仍归属于该用户的记录，不依赖文件事务回滚。
     */
    @Transactional
    public void deleteCreatedForCompensation(Long userId, Long resumeId) {
        // 上传失败补偿仍按用户归属锁定，重复执行时不存在即视为已清理。
        Resume resume = resumeRepository.findByIdAndUserIdForUpdate(resumeId, userId).orElse(null);
        if (resume == null) {
            return;
        }
        deleteRelations(resumeId, userId);
        resumeRepository.delete(resume);
        resumeRepository.flush();
    }

    /** 按分析、草稿、正式画像顺序删除用户拥有的关联记录，不处理物理文件。 */
    private void deleteRelations(Long resumeId, Long userId) {
        // 三组归属查询与条件删除共同完成关联清理；任一记录不存在时直接跳过，不触碰文件系统。
        analysisRepository.findByResumeIdAndUserId(resumeId, userId).ifPresent(analysisRepository::delete);
        draftRepository.findByResumeIdAndUserId(resumeId, userId).ifPresent(draftRepository::delete);
        profileRepository.findByResumeIdAndUserId(resumeId, userId).ifPresent(profileRepository::delete);
    }

    /**
     * 正式事实事务提交后的非敏感结果，交给提交服务判断是否尝试可选 INITIAL。
     *
     * @param resumeId 已确认画像所属且由当前用户拥有的简历标识
     * @param profileHash 正式事实 JSON 的 SHA-256 摘要，用于隔离旧辅助分析结果和任务
     * @param initialEligible 当前事务观察到无运行任务且首次模型调用未发生时为 true；提交服务仍会锁内复查
     */
    public record ConfirmedResume(Long resumeId, String profileHash, boolean initialEligible) {
    }
}
