package com.interviewcoach.interview.application.service;

import com.interviewcoach.common.exception.BusinessException;
import com.interviewcoach.interview.domain.agent.CoordinatorAgent.TurnResult;
import com.interviewcoach.interview.domain.entity.Interview;
import com.interviewcoach.interview.domain.entity.InterviewMessage;
import com.interviewcoach.interview.domain.entity.InterviewPhase;
import com.interviewcoach.interview.domain.entity.InterviewStatus;
import com.interviewcoach.interview.domain.model.InterviewContext;
import com.interviewcoach.interview.domain.repository.InterviewMessageRepository;
import com.interviewcoach.interview.domain.repository.InterviewRepository;
import com.interviewcoach.position.domain.entity.Position;
import com.interviewcoach.position.domain.repository.PositionRepository;
import com.interviewcoach.resume.domain.entity.Resume;
import com.interviewcoach.resume.domain.repository.ResumeRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 用短事务预留并写回单轮回答；Coordinator 和模型调用始终位于事务外。
 */
@Service
@RequiredArgsConstructor
public class InterviewTurnStateService {

    private static final String TURN_RESERVATION_PREFIX = "__TURN_RESERVATION__:";

    private final InterviewRepository interviewRepository;
    private final InterviewMessageRepository messageRepository;
    private final ResumeRepository resumeRepository;
    private final PositionRepository positionRepository;

    /**
     * 锁定本人面试并写入当前 turn token，使重复回答在模型调用前快速失败。
     */
    @Transactional
    public ReservedTurn reserve(Long userId, Long interviewId) {
        Interview interview = findInterviewForUpdate(userId, interviewId);
        requireInProgress(interview);
        if (interview.getTotalQuestionCount() == null || interview.getTotalQuestionCount() < 1) {
            throw stateConflict("面试仍在初始化，请稍后重试");
        }
        if (interview.getPendingQuestion() != null
                && !interview.getPendingQuestion().isBlank()) {
            throw stateConflict("上一轮回答仍在处理中，请稍后重试");
        }

        String reservationToken = TURN_RESERVATION_PREFIX + UUID.randomUUID();
        interview.setPendingQuestion(reservationToken);
        interviewRepository.save(interview);

        List<InterviewMessage> messages = messageRepository
                .findByInterviewIdOrderBySeqNoAsc(interviewId);
        String lastQuestion = messages.stream()
                .filter(message -> "interviewer".equals(message.getRole()))
                .reduce((left, right) -> right)
                .map(InterviewMessage::getContent)
                .orElseThrow(() -> stateConflict("当前面试问题尚未就绪"));
        return new ReservedTurn(
                interview,
                reservationToken,
                interview.getTotalQuestionCount(),
                interview.getCurrentPhase(),
                interview.getCurrentTopicId(),
                interview.getCurrentTopicName(),
                interview.getCurrentDepth(),
                lastQuestion);
    }

    /**
     * 只在 token、状态和问题计数仍匹配时，原子保存回答、下一题和面试上下文。
     */
    @Transactional
    public void complete(
            Long userId,
            ReservedTurn reserved,
            String answerText,
            InterviewContext context,
            TurnResult result) {
        Interview interview = findInterviewForUpdate(userId, reserved.interview().getId());
        requireInProgress(interview);
        if (!Objects.equals(interview.getPendingQuestion(), reserved.reservationToken())
                || !Objects.equals(interview.getTotalQuestionCount(), reserved.questionCount())
                || context == null
                || !Objects.equals(context.getInterviewId(), interview.getId())) {
            throw stateConflict("面试回答状态已变化，请刷新后重试");
        }

        int answerSeqNo = reserved.questionCount() + 1;
        saveMessage(
                interview.getId(),
                reserved.answerPhase(),
                "candidate",
                answerText,
                reserved.answerTopicId(),
                reserved.answerTopicName(),
                reserved.answerDepth(),
                answerSeqNo);
        saveMessage(
                interview.getId(),
                result.getPhase(),
                "interviewer",
                result.getQuestion(),
                result.getTopicId(),
                result.getTopicName(),
                result.getDepth(),
                answerSeqNo + 1);

        interview.setCurrentPhase(result.getPhase());
        interview.setCurrentTopicId(result.getTopicId());
        interview.setCurrentTopicName(result.getTopicName());
        interview.setCurrentDepth(result.getDepth());
        interview.setCurrentTopicFollowUpCount(context.getCurrentTopicFollowUpCount());
        interview.setConsecutiveFailures(context.getConsecutiveFailures());
        interview.setConsecutiveExcellence(context.getConsecutiveExcellence());
        interview.setLastEvaluationSeq(context.getLastEvaluationSeq());
        interview.setTotalQuestionCount(reserved.questionCount() + 2);
        interview.setCurrentPhaseQuestionCount(context.getCurrentPhaseQuestionCount());
        interview.setSelfIntroQuestionCount(context.getSelfIntroQuestionCount());
        interview.setCurrentProjectIndex(context.getCurrentProjectIndex());
        interview.setCurrentBehavioralIndex(context.getCurrentBehavioralIndex());
        if (result.getPreviousPhase() != null) {
            interview.setCurrentPhaseQuestionCount(0);
        }
        interview.setPendingQuestion(null);
        if (result.getPhase() == InterviewPhase.ENDING) {
            interview.setStatus(InterviewStatus.ENDED);
            interview.setEndedAt(LocalDateTime.now());
            unlockResumeAndPosition(interview);
        }
        interviewRepository.save(interview);
    }

    /**
     * 模型或写回失败时只释放仍属于本次调用的 token，不能清除后续 turn。
     */
    @Transactional
    public void release(Long userId, Long interviewId, String reservationToken) {
        Interview interview = interviewRepository
                .findByIdAndUserIdForUpdate(interviewId, userId)
                .orElse(null);
        if (interview == null
                || !Objects.equals(interview.getPendingQuestion(), reservationToken)) {
            return;
        }
        interview.setPendingQuestion(null);
        interviewRepository.save(interview);
    }

    /**
     * 内部 reservation 不属于断线恢复问题，接口响应必须隐藏该实现标记。
     */
    static boolean isTurnReservation(String pendingQuestion) {
        return pendingQuestion != null && pendingQuestion.startsWith(TURN_RESERVATION_PREFIX);
    }

    private Interview findInterviewForUpdate(Long userId, Long interviewId) {
        return interviewRepository.findByIdAndUserIdForUpdate(interviewId, userId)
                .orElseThrow(() -> new BusinessException(
                        InterviewErrorCode.INTERVIEW_NOT_FOUND.getCode(), "面试不存在"));
    }

    private void requireInProgress(Interview interview) {
        if (interview.getStatus() == InterviewStatus.ENDED) {
            throw new BusinessException(
                    InterviewErrorCode.INTERVIEW_ENDED.getCode(), "面试已结束");
        }
        if (interview.getStatus() == InterviewStatus.INTERRUPTED) {
            throw new BusinessException(
                    InterviewErrorCode.INTERVIEW_INTERRUPTED.getCode(), "面试已中断");
        }
    }

    private BusinessException stateConflict(String message) {
        return new BusinessException(
                InterviewErrorCode.INTERVIEW_STATE_CONFLICT.getCode(), message);
    }

    private void saveMessage(
            Long interviewId,
            InterviewPhase phase,
            String role,
            String content,
            String topicId,
            String topicName,
            Integer depth,
            int seqNo) {
        InterviewMessage message = new InterviewMessage();
        message.setInterviewId(interviewId);
        message.setPhase(phase.name());
        message.setRole(role);
        message.setContent(content);
        message.setTopicId(topicId);
        message.setTopicName(topicName);
        message.setDepth(depth);
        message.setSeqNo(seqNo);
        messageRepository.save(message);
    }

    private void unlockResumeAndPosition(Interview interview) {
        Resume resume = resumeRepository
                .findByIdAndUserIdForUpdate(interview.getResumeId(), interview.getUserId())
                .orElse(null);
        if (resume != null && interview.getId().equals(resume.getLockInterviewId())) {
            resume.setLockInterviewId(null);
            resumeRepository.save(resume);
        }
        Position position = positionRepository.findOwnedPersonalByIdForUpdate(
                        interview.getPositionId(), interview.getUserId())
                .orElse(null);
        if (position != null && interview.getId().equals(position.getLockInterviewId())) {
            position.setLockInterviewId(null);
            positionRepository.save(position);
        }
    }

    public record ReservedTurn(
            Interview interview,
            String reservationToken,
            Integer questionCount,
            InterviewPhase answerPhase,
            String answerTopicId,
            String answerTopicName,
            Integer answerDepth,
            String lastQuestion) {
    }
}
