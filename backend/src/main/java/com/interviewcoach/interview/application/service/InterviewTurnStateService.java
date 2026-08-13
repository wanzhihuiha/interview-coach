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
 * 单轮回答的持久化状态服务。
 *
 * <p>应用服务先调用本服务在短事务内写入一次性 reservation，再在事务外执行 Coordinator
 * 和模型调用，最后回到本服务原子保存候选人回答、下一题和推进后的上下文。失败补偿只会
 * 释放仍属于原调用的 reservation，避免并发请求互相清除状态。</p>
 */
@Service
@RequiredArgsConstructor
public class InterviewTurnStateService {

    /**
     * 存入 {@code pendingQuestion} 的内部轮次预留前缀；接口层识别该前缀后会隐藏整个值。
     */
    private static final String TURN_RESERVATION_PREFIX = "__TURN_RESERVATION__:";

    /** 负责按用户加锁读取面试并保存 reservation 与推进状态。 */
    private final InterviewRepository interviewRepository;

    /** 负责按序读取历史消息并成对保存本轮回答和下一题。 */
    private final InterviewMessageRepository messageRepository;

    /** 负责自然结束时精确释放简历锁。 */
    private final ResumeRepository resumeRepository;

    /** 负责自然结束时精确释放当前用户私有岗位锁。 */
    private final PositionRepository positionRepository;

    /**
     * 锁定本人面试并写入当前轮次 token，使重复回答在模型调用前快速失败。
     *
     * <p>会话必须已写入首题且没有待处理标记。写入 reservation 后读取按序消息中的最后一条
     * 面试官问题，并把回答所属环节、主题、深度和问题计数一起固化到返回值。</p>
     */
    @Transactional
    public ReservedTurn reserve(Long userId, Long interviewId) {
        // 加写锁复核归属与状态，防止两个回答请求同时取得同一轮次。
        Interview interview = findInterviewForUpdate(userId, interviewId);
        requireInProgress(interview);
        if (interview.getTotalQuestionCount() == null || interview.getTotalQuestionCount() < 1) {
            throw stateConflict("面试仍在初始化，请稍后重试");
        }
        if (interview.getPendingQuestion() != null
                && !interview.getPendingQuestion().isBlank()) {
            throw stateConflict("上一轮回答仍在处理中，请稍后重试");
        }

        // 将随机 token 持久化为当前轮次所有权，后续完成或释放都必须精确匹配该值。
        String reservationToken = TURN_RESERVATION_PREFIX + UUID.randomUUID();
        interview.setPendingQuestion(reservationToken);
        interviewRepository.save(interview);

        // 按消息序号读取历史，定位本次回答对应的最近一条面试官问题。
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
     *
     * <p>候选人回答使用预留时记录的旧环节状态，下一题使用 Coordinator 返回的新状态；
     * 两条消息采用相邻序号。进入结束环节时，同时结束会话并精确释放简历和私有岗位锁。</p>
     */
    @Transactional
    public void complete(
            Long userId,
            ReservedTurn reserved,
            String answerText,
            InterviewContext context,
            TurnResult result) {
        // 重新加锁并比较 reservation、问题计数和上下文归属，拒绝过期模型结果写回。
        Interview interview = findInterviewForUpdate(userId, reserved.interview().getId());
        requireInProgress(interview);
        if (!Objects.equals(interview.getPendingQuestion(), reserved.reservationToken())
                || !Objects.equals(interview.getTotalQuestionCount(), reserved.questionCount())
                || context == null
                || !Objects.equals(context.getInterviewId(), interview.getId())) {
            throw stateConflict("面试回答状态已变化，请刷新后重试");
        }

        // 在同一事务中先保存回答、再保存下一题，双方消息共享连续的面试级序号。
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

        // 将 Coordinator 已改变的运行上下文写回实体，供断线后的下一轮重新初始化。
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
            // 结束题落库后把会话置为正常结束，并在同一事务释放本场资源锁。
            interview.setStatus(InterviewStatus.ENDED);
            interview.setEndedAt(LocalDateTime.now());
            unlockResumeAndPosition(interview);
        }
        interviewRepository.save(interview);
    }

    /**
     * 模型或写回失败时只释放仍属于本次调用的 token，不能清除后续轮次。
     * 面试不存在或 token 已变化时按幂等结果直接返回。
     */
    @Transactional
    public void release(Long userId, Long interviewId, String reservationToken) {
        // 锁定会话并精确匹配 token，避免迟到的补偿释放后来请求的 reservation。
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
     * 判断 {@code pendingQuestion} 是否为内部 reservation。
     * 该标记不是可恢复的问题正文，详情响应必须隐藏它。
     */
    static boolean isTurnReservation(String pendingQuestion) {
        return pendingQuestion != null && pendingQuestion.startsWith(TURN_RESERVATION_PREFIX);
    }

    /** 按用户归属加写锁查询会话，不存在或不属于当前用户时统一返回“面试不存在”。 */
    private Interview findInterviewForUpdate(Long userId, Long interviewId) {
        return interviewRepository.findByIdAndUserIdForUpdate(interviewId, userId)
                .orElseThrow(() -> new BusinessException(
                        InterviewErrorCode.INTERVIEW_NOT_FOUND.getCode(), "面试不存在"));
    }

    /** 拒绝已结束或已中断会话；进行中状态继续交给 reservation 条件判断。 */
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

    /** 使用统一状态冲突错误码构造当前轮次的并发失败。 */
    private BusinessException stateConflict(String message) {
        return new BusinessException(
                InterviewErrorCode.INTERVIEW_STATE_CONFLICT.getCode(), message);
    }

    /**
     * 按调用方给出的旧/新环节快照持久化一条消息；本方法不自行分配序号或校验角色编码。
     */
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
        // 消息保存参加 complete 的事务，与另一条消息及面试状态共同提交或回滚。
        messageRepository.save(message);
    }

    /**
     * 面试自然结束时精确释放简历和私有岗位锁；公共岗位不会被私有岗位查询命中。
     */
    private void unlockResumeAndPosition(Interview interview) {
        // 锁字段必须仍等于本面试 ID，防止清除后来面试已经取得的资源锁。
        Resume resume = resumeRepository
                .findByIdAndUserIdForUpdate(interview.getResumeId(), interview.getUserId())
                .orElse(null);
        if (resume != null && interview.getId().equals(resume.getLockInterviewId())) {
            resume.setLockInterviewId(null);
            resumeRepository.save(resume);
        }
        // 创建流程只锁定私有岗位，因此释放也限定为当前用户拥有的私有岗位。
        Position position = positionRepository.findOwnedPersonalByIdForUpdate(
                        interview.getPositionId(), interview.getUserId())
                .orElse(null);
        if (position != null && interview.getId().equals(position.getLockInterviewId())) {
            position.setLockInterviewId(null);
            positionRepository.save(position);
        }
    }

    /**
     * 事务外 Coordinator 调用所需的不可变轮次快照。
     *
     * @param interview reservation 写入后的面试实体快照
     * @param reservationToken 本轮所有权 token，完成和失败释放时必须精确匹配
     * @param questionCount 预留时的消息计数，用于分配后续序号并检测并发变化
     * @param answerPhase 候选人回答所对应的旧问题环节
     * @param answerTopicId 候选人回答所对应的旧主题标识
     * @param answerTopicName 候选人回答所对应的旧主题名称
     * @param answerDepth 候选人回答所对应的旧问题深度
     * @param lastQuestion 本轮候选人正在回答的最近一条面试官问题
     */
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
