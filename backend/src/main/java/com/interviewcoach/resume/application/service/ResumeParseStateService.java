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
 * 为提交服务与事实解析 Worker 提供简历解析状态的数据库短事务边界。
 * 所有读写都带用户归属，并以 generation 区分新旧任务，使旧 Worker 结果不能覆盖当前草稿；
 * 文件读取、模型调用和 Redis 额度结算均由事务外调用方负责。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ResumeParseStateService {

    /** 按用户归属查询并锁定简历、保存解析状态和额度恢复凭据的仓储。 */
    private final ResumeRepository resumeRepository;
    /** 将 Worker 结果规范化后保存为草稿，并根据事实派生岗位分类的画像组件。 */
    private final ResumeProfileSupport profileSupport;

    /**
     * 在取得 Redis 资源前只读校验手动重解析资格；真正登记时仍会在锁内复查。
     */
    @Transactional(readOnly = true)
    public void validateManualReparse(Long resumeId, Long userId) {
        // 归属查询同时隐藏其他用户是否存在该简历；不存在统一返回本用户资源不存在。
        Resume resume = resumeRepository.findByIdAndUserId(resumeId, userId)
                .orElseThrow(() -> new BusinessException(ResumeErrorCode.RESUME_NOT_FOUND, "简历不存在"));
        // 在 Redis 资源申请前拒绝锁定、正在解析或仍有额度凭据的简历。
        validateCanReparse(resume);
    }

    /**
     * 取得并发许可和额度后，在短事务内登记新的手动事实解析代次及恢复元数据。
     */
    @Transactional
    public PreparedParse prepareManualReparse(
            Long resumeId, Long userId, ResumeAiQuotaReservation quotaReservation) {
        // 锁住用户拥有的简历并重新执行资格校验，封闭预检与实际登记之间的并发窗口。
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
        // 将新代次、PENDING 状态和 Redis 恢复凭据一起提交，供 Worker 或启动恢复读取。
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
        // 以用户归属锁定数据库记录；缺失、非 PENDING 或代次不符均视为过期任务。
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
        // 认领任务后写入 PARSING 与开始时间，启动恢复可据此把进程中断任务标为失败。
        resumeRepository.save(resume);
        ResumeAiQuotaReservation quotaReservation = resume.getParseQuotaDate() == null
                || resume.getParseQuotaToken() == null
                ? null
                : new ResumeAiQuotaReservation(
                        resume.getParseQuotaDate(), resume.getParseQuotaToken());
        return new ParseInput(resume.getFilePath(), resume.getFileType(), quotaReservation);
    }

    /**
     * 只把当前代次的 PARSING 结果写成草稿；quota token 保留到统一结算器确认可清理后再删除。
     */
    @Transactional
    public boolean complete(
            Long resumeId, Long userId, Long generation, UserProfileData profileData) {
        // 锁定当前归属记录，并只接受当前 generation 仍处于 PARSING 的 Worker 结果。
        Resume resume = resumeRepository.findByIdAndUserIdForUpdate(resumeId, userId).orElse(null);
        if (resume == null || resume.getParseStatus() != ResumeParseStatus.PARSING
                || !Objects.equals(resume.getParseGeneration(), generation)) {
            log.warn("[ResumeParse] 丢弃过期解析结果: resumeId={}, generation={}", resumeId, generation);
            return false;
        }
        // 先保存当前代次草稿，再把简历切到待确认；任一步异常都会回滚同一本地事务。
        profileSupport.saveDraft(resumeId, userId, generation, profileData);
        // 岗位分类由同一份规范化事实派生并随解析状态一起保存。
        resume.setJobCategory(profileSupport.inferJobCategory(profileData));
        resume.setParseStatus(ResumeParseStatus.PENDING_CONFIRM);
        resume.setParseStartedAt(null);
        resume.setParseErrorCode(null);
        resume.setParseErrorMessage(null);
        // 数据库成功写回不清额度 token，调用方需在同日 Redis 转换成功或额度日期关闭后单独清理。
        resumeRepository.save(resume);
        return true;
    }

    /**
     * 兼容旧调用：只失败当前代次仍在等待或执行中的任务，并保留 quota 恢复凭据。
     */
    @Transactional
    public void fail(Long resumeId, Long userId, Long generation, String errorCode, String errorMessage) {
        // 兼容入口仍以用户归属和 generation 锁定记录，旧任务不会修改当前状态。
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
        // 同时校验归属、代次和数据库额度凭据，避免把另一任务的 Redis 预留错误结算。
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
     * 统一结算器确认同日 Redis 转换成功或额度日期已关闭后，按归属、代次、日期和 token 幂等清理恢复凭据。
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
        // 结算器允许清理后再次锁定并精确匹配任务凭据，错配或重复请求保持无副作用。
        Resume resume = resumeRepository.findByIdAndUserIdForUpdate(resumeId, userId).orElse(null);
        if (resume == null
                || !Objects.equals(resume.getParseGeneration(), generation)
                || !Objects.equals(resume.getParseQuotaDate(), expectedReservation.quotaDate())
                || !Objects.equals(resume.getParseQuotaToken(), expectedReservation.quotaToken())) {
            return;
        }
        resume.setParseQuotaDate(null);
        resume.setParseQuotaToken(null);
        // 只清恢复凭据，不改解析终态或草稿。
        resumeRepository.save(resume);
    }

    /** 校验手动重解析的数据库侧守卫；不申请或释放任何 Redis 资源。 */
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

    /** 仅把 PENDING 或 PARSING 改为失败并保存错误，其余终态保持不变。 */
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

    /** 判断数据库中的日期和 token 是否完整对应调用方要结算的 Redis 预留。 */
    private boolean quotaMatches(Resume resume, ResumeAiQuotaReservation expectedReservation) {
        if (expectedReservation == null) {
            return resume.getParseQuotaDate() == null && resume.getParseQuotaToken() == null;
        }
        return Objects.equals(resume.getParseQuotaDate(), expectedReservation.quotaDate())
                && Objects.equals(resume.getParseQuotaToken(), expectedReservation.quotaToken());
    }

    /**
     * 当前解析任务认领成功后交给 Worker 的事务外输入。
     *
     * @param filePath 待提取简历文件的服务端存储路径
     * @param fileType 指示文本提取器选择 PDF 或 TXT 分支的文件类型
     * @param quotaReservation 数据库保存的额度恢复凭据；首次上传任务没有手动额度时为 null
     */
    public record ParseInput(
            String filePath,
            String fileType,
            ResumeAiQuotaReservation quotaReservation) {
    }

    /**
     * 手动重解析登记事务的返回结果，供提交服务发布同代次事件。
     *
     * @param resume 已进入 PENDING 且携带新代次的持久化简历
     * @param event 与该简历代次和额度预留对应、尚未附加任务许可的事件
     */
    public record PreparedParse(Resume resume, ResumeParseRequestedEvent event) {
    }
}
