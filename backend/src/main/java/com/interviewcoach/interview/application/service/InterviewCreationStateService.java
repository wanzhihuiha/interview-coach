package com.interviewcoach.interview.application.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.interviewcoach.common.exception.BusinessException;
import com.interviewcoach.interview.domain.entity.Interview;
import com.interviewcoach.interview.domain.entity.InterviewMessage;
import com.interviewcoach.interview.domain.entity.InterviewPhase;
import com.interviewcoach.interview.domain.entity.InterviewStatus;
import com.interviewcoach.interview.domain.model.InterviewContext;
import com.interviewcoach.interview.domain.repository.InterviewMessageRepository;
import com.interviewcoach.interview.domain.repository.InterviewRepository;
import com.interviewcoach.position.domain.entity.Position;
import com.interviewcoach.position.domain.entity.PositionProfile;
import com.interviewcoach.position.domain.model.PositionProfileData;
import com.interviewcoach.position.domain.repository.PositionProfileRepository;
import com.interviewcoach.position.domain.repository.PositionRepository;
import com.interviewcoach.resume.application.service.ResumeProfileAnalysisStateService;
import com.interviewcoach.resume.domain.entity.Resume;
import com.interviewcoach.resume.domain.entity.ResumeProfile;
import com.interviewcoach.resume.domain.model.ResumeProfileAnalysisData;
import com.interviewcoach.resume.domain.model.UserProfileData;
import com.interviewcoach.resume.domain.repository.ResumeProfileRepository;
import com.interviewcoach.resume.domain.repository.ResumeRepository;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 面试创建阶段的持久化状态服务。
 *
 * <p>应用服务先调用本服务在短事务内锁定简历和私有岗位、固化画像快照并创建空会话，
 * 再在事务外生成首题，最后回到本服务写入首题和运行上下文。模型调用失败时，应用服务
 * 通过本服务把已准备的会话标记为中断并精确释放锁定。</p>
 */
@Service
@RequiredArgsConstructor
public class InterviewCreationStateService {

    /** 负责创建、加锁读取和更新面试会话。 */
    private final InterviewRepository interviewRepository;

    /** 负责持久化创建成功后的第一条面试官消息。 */
    private final InterviewMessageRepository messageRepository;

    /** 负责按当前用户锁定简历并维护其面试锁 ID。 */
    private final ResumeRepository resumeRepository;

    /** 负责读取当前简历已经确认的正式事实画像。 */
    private final ResumeProfileRepository resumeProfileRepository;

    /** 负责读取可用于本场选题的当前简历辅助分析；分析不可用时允许为空。 */
    private final ResumeProfileAnalysisStateService resumeProfileAnalysisStateService;

    /** 负责锁定当前可访问岗位，并维护私有岗位的面试锁 ID。 */
    private final PositionRepository positionRepository;

    /** 负责读取岗位的正式画像数据。 */
    private final PositionProfileRepository positionProfileRepository;

    /** 负责校验画像 JSON 及生成会话内不可变快照。 */
    private final ObjectMapper objectMapper;

    /**
     * 按 Resume -> Position 的固定顺序加锁并准备一场尚未写入首题的面试。
     *
     * <p>简历必须属于当前用户；岗位必须是当前用户可访问且未归档的私有或公共岗位。
     * 私有岗位参与锁定，公共岗位不会写入岗位锁。正式事实画像和岗位画像是创建前提，
     * 可用的辅助分析只作为选题快照，缺失不会阻断创建。</p>
     *
     * @param userId 当前登录用户 ID
     * @param resumeId 用户选定的简历 ID
     * @param positionId 用户选定的私有或公共岗位 ID
     * @param selectedPhases 应用服务整理后的实际环节顺序
     * @return 已落库的空面试及本场使用的三份结构化画像数据
     */
    @Transactional
    public PreparedInterview prepare(
            Long userId,
            Long resumeId,
            Long positionId,
            List<InterviewPhase> selectedPhases) {
        if (selectedPhases == null || selectedPhases.isEmpty()) {
            throw new BusinessException(
                    InterviewErrorCode.NO_PHASE_SELECTED.getCode(), "未选择任何环节");
        }

        // 先锁定本人简历，统一锁顺序并在创建前清理不再指向进行中面试的旧锁。
        Resume resume = resumeRepository.findByIdAndUserIdForUpdate(resumeId, userId)
                .orElseThrow(() -> new BusinessException(
                        InterviewErrorCode.RESUME_NOT_FOUND.getCode(), "简历不存在"));
        clearStaleResumeLock(resume);
        if (resume.isLocked()) {
            throw new BusinessException(
                    InterviewErrorCode.RESUME_LOCKED.getCode(), "简历已锁定在其他面试");
        }

        // 再锁定当前可访问且未归档的岗位；固定 Resume -> Position 顺序以收敛并发锁顺序。
        Position position = positionRepository
                .findInterviewAccessibleByIdForUpdate(positionId, userId)
                .orElseThrow(() -> new BusinessException(
                        InterviewErrorCode.POSITION_NOT_FOUND.getCode(), "岗位不存在"));
        boolean personalPosition = Boolean.FALSE.equals(position.getIsPublic());
        if (personalPosition) {
            // 公共岗位可被多场面试复用，只有私有岗位需要清理和检查独占锁。
            clearStalePositionLock(position);
            if (position.isLocked()) {
                throw new BusinessException(
                        InterviewErrorCode.POSITION_LOCKED.getCode(), "岗位已锁定在其他面试");
            }
        }

        // 正式简历画像是面试事实输入，创建事务会重新核验其存在性和用户归属。
        ResumeProfile resumeProfile = resumeProfileRepository
                .findByResumeIdAndUserId(resume.getId(), userId)
                .orElseThrow(() -> new BusinessException(
                        InterviewErrorCode.RESUME_STATUS_INVALID.getCode(), "简历画像未就绪"));
        // 岗位必须已经生成正式画像；公共岗位缺画像时按不可访问收口，避免暴露内部状态。
        PositionProfile positionProfile = positionProfileRepository
                .findByPositionId(position.getId())
                .orElseThrow(() -> new BusinessException(
                        personalPosition
                                ? InterviewErrorCode.POSITION_STATUS_INVALID.getCode()
                                : InterviewErrorCode.POSITION_NOT_FOUND.getCode(),
                        personalPosition ? "岗位画像未就绪" : "岗位不存在"));

        // 严格解析两份必需画像，坏 JSON 不会降级成空对象或回查后续实时数据。
        UserProfileData userProfileData = requireSnapshotJson(
                resumeProfile.getProfileData(), UserProfileData.class, "简历画像数据异常");
        // 辅助分析只有当前视图明确可用于面试时才快照；缺失、失败或不可用均保留为空。
        ResumeProfileAnalysisData analysisData = resumeProfileAnalysisStateService
                .loadCurrent(resume.getId(), userId)
                .filter(ResumeProfileAnalysisStateService.AnalysisView::usableForInterview)
                .map(ResumeProfileAnalysisStateService.AnalysisView::data)
                .orElse(null);
        PositionProfileData positionProfileData = requireSnapshotJson(
                positionProfile.getProfileData(), PositionProfileData.class, "岗位画像数据异常");

        // 把创建时看到的展示字段、画像和环节顺序固化到会话，后续轮次不再依赖实时资源。
        Interview interview = new Interview();
        interview.setUserId(userId);
        interview.setResumeId(resume.getId());
        interview.setPositionId(position.getId());
        interview.setPositionNameSnapshot(requireText(
                position.getPositionName(), "岗位名称异常"));
        interview.setCompanyNameSnapshot(trimToNull(position.getCompanyName()));
        interview.setJobCategorySnapshot(requireText(
                position.getJobCategory(), "岗位类别异常"));
        interview.setUserProfileSnapshot(toJson(userProfileData));
        interview.setUserProfileAnalysisSnapshot(
                analysisData == null ? null : toJson(analysisData));
        interview.setPositionProfileSnapshot(toJson(positionProfileData));
        interview.setSelectedPhases(toJson(
                selectedPhases.stream().map(InterviewPhase::name).toList()));
        interview.setCurrentPhase(selectedPhases.get(0));
        interview.setStatus(InterviewStatus.IN_PROGRESS);
        interview.setTotalQuestionCount(0);
        interview.setCurrentPhaseQuestionCount(0);
        // 立即刷新以取得面试 ID，随后简历和私有岗位用该 ID 标记本场独占关系。
        interviewRepository.saveAndFlush(interview);

        // 简历始终被本场进行中面试锁定；事务失败时该写入与面试创建一起回滚。
        resume.setLockInterviewId(interview.getId());
        resumeRepository.save(resume);
        if (personalPosition) {
            // 公共岗位不占锁；只有当前用户拥有的私有岗位记录面试锁 ID。
            position.setLockInterviewId(interview.getId());
            positionRepository.save(position);
        }
        return new PreparedInterview(
                interview, userProfileData, analysisData, positionProfileData);
    }

    /**
     * 首题在事务外生成后，锁定面试并原子保存首题和初始运行上下文。
     *
     * <p>仅接受仍处于进行中、问题计数为 0 且上下文属于该面试的准备记录；并发写回或
     * 重复调用会以状态冲突失败。成功后第一条面试官消息序号为 1，面试问题计数同步置为 1。</p>
     */
    @Transactional
    public Interview completeFirstQuestion(
            Long userId,
            Long interviewId,
            String firstQuestion,
            InterviewContext context) {
        // 加写锁复核准备记录，防止首题重复落库或覆盖已经推进的会话。
        Interview interview = interviewRepository
                .findByIdAndUserIdForUpdate(interviewId, userId)
                .orElseThrow(() -> new BusinessException(
                        InterviewErrorCode.INTERVIEW_NOT_FOUND.getCode(), "面试不存在"));
        if (interview.getStatus() != InterviewStatus.IN_PROGRESS
                || interview.getTotalQuestionCount() == null
                || interview.getTotalQuestionCount() != 0) {
            throw new BusinessException(
                    InterviewErrorCode.INTERVIEW_STATE_CONFLICT.getCode(),
                    "面试初始化状态已变化");
        }
        if (context == null || !interviewId.equals(context.getInterviewId())) {
            throw new BusinessException(
                    InterviewErrorCode.INTERVIEW_STATE_CONFLICT.getCode(),
                    "面试初始化上下文无效");
        }

        // 首题与上下文状态在同一事务写入，任何保存失败都会一起回滚。
        InterviewMessage firstMessage = new InterviewMessage();
        firstMessage.setInterviewId(interview.getId());
        InterviewPhase currentPhase = context.getCurrentPhase() == null
                ? interview.getCurrentPhase() : context.getCurrentPhase();
        firstMessage.setPhase(currentPhase.name());
        firstMessage.setRole("interviewer");
        firstMessage.setContent(requireText(firstQuestion, "首题生成结果为空"));
        firstMessage.setTopicId(context.getCurrentTopicId());
        firstMessage.setTopicName(context.getCurrentTopicName());
        firstMessage.setDepth(context.getCurrentDepth());
        firstMessage.setSeqNo(1);
        messageRepository.save(firstMessage);

        // 将 Coordinator 初始化后的 Skill 状态写回，下一轮可从数据库恢复同一推进位置。
        interview.setCurrentPhase(currentPhase);
        interview.setCurrentTopicId(context.getCurrentTopicId());
        interview.setCurrentTopicName(context.getCurrentTopicName());
        interview.setCurrentDepth(context.getCurrentDepth());
        interview.setCurrentTopicFollowUpCount(context.getCurrentTopicFollowUpCount());
        interview.setConsecutiveFailures(context.getConsecutiveFailures());
        interview.setConsecutiveExcellence(context.getConsecutiveExcellence());
        interview.setLastEvaluationSeq(context.getLastEvaluationSeq());
        interview.setCurrentPhaseQuestionCount(context.getCurrentPhaseQuestionCount());
        interview.setSelfIntroQuestionCount(context.getSelfIntroQuestionCount());
        interview.setCurrentProjectIndex(context.getCurrentProjectIndex());
        interview.setCurrentBehavioralIndex(context.getCurrentBehavioralIndex());
        interview.setTotalQuestionCount(1);
        return interviewRepository.save(interview);
    }

    /**
     * 初始化失败时按 Interview -> Resume -> Position 顺序标记中断并释放本次精确锁定。
     *
     * <p>面试不存在或已经离开进行中状态时按幂等结果返回；锁字段只有仍等于本面试 ID 时
     * 才清除，避免误释放后来创建的面试。公共岗位不会被该私有岗位查询命中。</p>
     */
    @Transactional
    public void interruptPreparedInterview(Long userId, Long interviewId) {
        // 锁定本人会话，只补偿仍处于本次创建中间态的记录。
        Interview interview = interviewRepository
                .findByIdAndUserIdForUpdate(interviewId, userId)
                .orElse(null);
        if (interview == null || interview.getStatus() != InterviewStatus.IN_PROGRESS) {
            return;
        }
        interview.setStatus(InterviewStatus.INTERRUPTED);
        interview.setEndedAt(LocalDateTime.now());
        interviewRepository.save(interview);

        // 精确比较锁持有者后释放简历，不能清除已经转交给其他面试的锁。
        Resume resume = resumeRepository
                .findByIdAndUserIdForUpdate(interview.getResumeId(), userId)
                .orElse(null);
        if (resume != null && interviewId.equals(resume.getLockInterviewId())) {
            resume.setLockInterviewId(null);
            resumeRepository.save(resume);
        }
        // 仅私有岗位会在创建时加锁，因此按本人私有岗位入口执行精确释放。
        Position position = positionRepository.findOwnedPersonalByIdForUpdate(
                        interview.getPositionId(), userId)
                .orElse(null);
        if (position != null && interviewId.equals(position.getLockInterviewId())) {
            position.setLockInterviewId(null);
            positionRepository.save(position);
        }
    }

    /** 清除未指向进行中面试的简历锁；仍活跃的锁保持不变并由调用方拒绝创建。 */
    private void clearStaleResumeLock(Resume resume) {
        if (resume.isLocked() && !isInterviewActive(resume.getLockInterviewId())) {
            resume.setLockInterviewId(null);
        }
    }

    /** 清除未指向进行中面试的私有岗位锁；公共岗位不会调用本方法。 */
    private void clearStalePositionLock(Position position) {
        if (position.isLocked() && !isInterviewActive(position.getLockInterviewId())) {
            position.setLockInterviewId(null);
        }
    }

    /** 按锁中保存的面试 ID 查询其是否仍处于进行中状态。 */
    private boolean isInterviewActive(Long interviewId) {
        return interviewId != null && interviewRepository.existsByIdAndStatus(
                interviewId, InterviewStatus.IN_PROGRESS);
    }

    /**
     * 严格解析必需画像 JSON，并兼容历史上被再次 JSON 编码成字符串的内容。
     * 空值、坏 JSON 或解析出的空对象统一转换为快照异常。
     */
    private <T> T requireSnapshotJson(String json, Class<T> type, String message) {
        if (json == null || json.isBlank()) {
            throw snapshotInvalid(message, null);
        }
        try {
            String normalized = json.trim();
            if (normalized.startsWith("\"") && normalized.endsWith("\"")) {
                normalized = objectMapper.readValue(normalized, String.class);
            }
            T value = objectMapper.readValue(normalized, type);
            if (value == null) {
                throw snapshotInvalid(message, null);
            }
            return value;
        } catch (JsonProcessingException e) {
            throw snapshotInvalid(message, e);
        }
    }

    /** 将结构化画像或环节列表序列化为会话快照，失败时阻断创建。 */
    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw snapshotInvalid("面试快照序列化失败", e);
        }
    }

    /** 去除展示快照两端空白并要求结果非空。 */
    private String requireText(String value, String message) {
        String normalized = trimToNull(value);
        if (normalized == null) {
            throw snapshotInvalid(message, null);
        }
        return normalized;
    }

    /** 把空白字符串归一化为 {@code null}，保留非空文本的去空白结果。 */
    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** 使用统一快照错误码构造业务异常，并在存在时保留 JSON 根因。 */
    private BusinessException snapshotInvalid(String message, Throwable cause) {
        return cause == null
                ? new BusinessException(
                        InterviewErrorCode.INTERVIEW_SNAPSHOT_INVALID.getCode(), message)
                : new BusinessException(
                        InterviewErrorCode.INTERVIEW_SNAPSHOT_INVALID.getCode(), message, cause);
    }

    /**
     * 创建事务交给事务外首题生成流程的原子准备结果。
     *
     * @param interview 已持久化且尚未写入首题的进行中面试
     * @param userProfile 创建时解析出的正式简历事实画像
     * @param userProfileAnalysis 创建时可用的辅助分析，不可用时为 {@code null}
     * @param positionProfile 创建时解析出的正式岗位画像
     */
    public record PreparedInterview(
            Interview interview,
            UserProfileData userProfile,
            ResumeProfileAnalysisData userProfileAnalysis,
            PositionProfileData positionProfile) {
    }
}
