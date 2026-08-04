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
 * 用短事务准备面试快照、写入首题并在创建失败时收口锁定，不执行任何模型调用。
 */
@Service
@RequiredArgsConstructor
public class InterviewCreationStateService {

    private final InterviewRepository interviewRepository;
    private final InterviewMessageRepository messageRepository;
    private final ResumeRepository resumeRepository;
    private final ResumeProfileRepository resumeProfileRepository;
    private final ResumeProfileAnalysisStateService resumeProfileAnalysisStateService;
    private final PositionRepository positionRepository;
    private final PositionProfileRepository positionProfileRepository;
    private final ObjectMapper objectMapper;

    /**
     * 按 Resume -> Position 顺序加锁，复查归属、公开、归档和正式画像后一次性固定快照。
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

        Resume resume = resumeRepository.findByIdAndUserIdForUpdate(resumeId, userId)
                .orElseThrow(() -> new BusinessException(
                        InterviewErrorCode.RESUME_NOT_FOUND.getCode(), "简历不存在"));
        clearStaleResumeLock(resume);
        if (resume.isLocked()) {
            throw new BusinessException(
                    InterviewErrorCode.RESUME_LOCKED.getCode(), "简历已锁定在其他面试");
        }

        Position position = positionRepository
                .findInterviewAccessibleByIdForUpdate(positionId, userId)
                .orElseThrow(() -> new BusinessException(
                        InterviewErrorCode.POSITION_NOT_FOUND.getCode(), "岗位不存在"));
        boolean personalPosition = Boolean.FALSE.equals(position.getIsPublic());
        if (personalPosition) {
            clearStalePositionLock(position);
            if (position.isLocked()) {
                throw new BusinessException(
                        InterviewErrorCode.POSITION_LOCKED.getCode(), "岗位已锁定在其他面试");
            }
        }

        ResumeProfile resumeProfile = resumeProfileRepository
                .findByResumeIdAndUserId(resume.getId(), userId)
                .orElseThrow(() -> new BusinessException(
                        InterviewErrorCode.RESUME_STATUS_INVALID.getCode(), "简历画像未就绪"));
        PositionProfile positionProfile = positionProfileRepository
                .findByPositionId(position.getId())
                .orElseThrow(() -> new BusinessException(
                        personalPosition
                                ? InterviewErrorCode.POSITION_STATUS_INVALID.getCode()
                                : InterviewErrorCode.POSITION_NOT_FOUND.getCode(),
                        personalPosition ? "岗位画像未就绪" : "岗位不存在"));

        UserProfileData userProfileData = requireSnapshotJson(
                resumeProfile.getProfileData(), UserProfileData.class, "简历画像数据异常");
        ResumeProfileAnalysisData analysisData = resumeProfileAnalysisStateService
                .loadCurrent(resume.getId(), userId)
                .filter(ResumeProfileAnalysisStateService.AnalysisView::usableForInterview)
                .map(ResumeProfileAnalysisStateService.AnalysisView::data)
                .orElse(null);
        PositionProfileData positionProfileData = requireSnapshotJson(
                positionProfile.getProfileData(), PositionProfileData.class, "岗位画像数据异常");

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
        interviewRepository.saveAndFlush(interview);

        resume.setLockInterviewId(interview.getId());
        resumeRepository.save(resume);
        if (personalPosition) {
            position.setLockInterviewId(interview.getId());
            positionRepository.save(position);
        }
        return new PreparedInterview(
                interview, userProfileData, analysisData, positionProfileData);
    }

    /**
     * 首题已经在事务外生成后，锁定面试并原子保存消息和上下文计数。
     */
    @Transactional
    public Interview completeFirstQuestion(
            Long userId,
            Long interviewId,
            String firstQuestion,
            InterviewContext context) {
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
     */
    @Transactional
    public void interruptPreparedInterview(Long userId, Long interviewId) {
        Interview interview = interviewRepository
                .findByIdAndUserIdForUpdate(interviewId, userId)
                .orElse(null);
        if (interview == null || interview.getStatus() != InterviewStatus.IN_PROGRESS) {
            return;
        }
        interview.setStatus(InterviewStatus.INTERRUPTED);
        interview.setEndedAt(LocalDateTime.now());
        interviewRepository.save(interview);

        Resume resume = resumeRepository
                .findByIdAndUserIdForUpdate(interview.getResumeId(), userId)
                .orElse(null);
        if (resume != null && interviewId.equals(resume.getLockInterviewId())) {
            resume.setLockInterviewId(null);
            resumeRepository.save(resume);
        }
        Position position = positionRepository.findOwnedPersonalByIdForUpdate(
                        interview.getPositionId(), userId)
                .orElse(null);
        if (position != null && interviewId.equals(position.getLockInterviewId())) {
            position.setLockInterviewId(null);
            positionRepository.save(position);
        }
    }

    private void clearStaleResumeLock(Resume resume) {
        if (resume.isLocked() && !isInterviewActive(resume.getLockInterviewId())) {
            resume.setLockInterviewId(null);
        }
    }

    private void clearStalePositionLock(Position position) {
        if (position.isLocked() && !isInterviewActive(position.getLockInterviewId())) {
            position.setLockInterviewId(null);
        }
    }

    private boolean isInterviewActive(Long interviewId) {
        return interviewId != null && interviewRepository.existsByIdAndStatus(
                interviewId, InterviewStatus.IN_PROGRESS);
    }

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

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw snapshotInvalid("面试快照序列化失败", e);
        }
    }

    private String requireText(String value, String message) {
        String normalized = trimToNull(value);
        if (normalized == null) {
            throw snapshotInvalid(message, null);
        }
        return normalized;
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private BusinessException snapshotInvalid(String message, Throwable cause) {
        return cause == null
                ? new BusinessException(
                        InterviewErrorCode.INTERVIEW_SNAPSHOT_INVALID.getCode(), message)
                : new BusinessException(
                        InterviewErrorCode.INTERVIEW_SNAPSHOT_INVALID.getCode(), message, cause);
    }

    public record PreparedInterview(
            Interview interview,
            UserProfileData userProfile,
            ResumeProfileAnalysisData userProfileAnalysis,
            PositionProfileData positionProfile) {
    }
}
