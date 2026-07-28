package com.interviewcoach.interview.domain.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.interviewcoach.interview.domain.agent.skill.InterviewSkill;
import com.interviewcoach.interview.domain.agent.skill.SkillRegistry;
import com.interviewcoach.interview.domain.entity.Interview;
import com.interviewcoach.interview.domain.entity.InterviewMessage;
import com.interviewcoach.interview.domain.entity.InterviewPhase;
import com.interviewcoach.interview.domain.entity.InterviewStatus;
import com.interviewcoach.interview.domain.model.EvaluationResult;
import com.interviewcoach.interview.domain.model.EvaluationSignal;
import com.interviewcoach.interview.domain.model.InterviewContext;
import com.interviewcoach.interview.domain.model.NextAction;
import com.interviewcoach.interview.domain.repository.InterviewMessageRepository;
import com.interviewcoach.common.security.agent.AgentContext;
import com.interviewcoach.common.security.agent.AgentType;
import com.interviewcoach.interview.infrastructure.tool.EvaluationFallbackTool;
import com.interviewcoach.position.domain.model.PositionProfileData;
import com.interviewcoach.position.domain.repository.PositionRepository;
import com.interviewcoach.resume.domain.model.UserProfileData;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 单轮面试流程的协调者。
 *
 * <p>上游由 {@code InterviewService} 调用。本组件根据面试记录和画像快照恢复上下文，
 * 岗位大类则根据岗位编号查询当前岗位记录，再把当前环节交给对应 {@code InterviewSkill}。
 * 每轮处理中，本组件负责保存候选人回答和下一题，
 * Skill 负责具体提问与切换规则，评估器及本地降级规则负责产生决策信号；面试实体的状态和计数
 * 最终仍由 {@code InterviewService} 根据返回结果保存。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CoordinatorAgent {

    private final EvaluatorAgent evaluatorAgent;
    private final EvaluationFallbackTool fallbackTool;
    private final SkillRegistry skillRegistry;
    private final InterviewMessageRepository messageRepository;
    private final PositionRepository positionRepository;
    private final ObjectMapper objectMapper;

    /**
     * 根据面试记录和本场画像快照构建协调上下文。
     *
     * <p>用户和岗位画像直接使用上游传入的本场快照，岗位大类例外：它会根据岗位编号查询当前岗位记录，
     * 查询不到或类别为 {@code null} 时使用 {@code GENERAL}。环节、主题及大部分进度从面试记录恢复，
     * {@code maxQuestions} 固定为 30，单主题最多追问 5 次，其他环节预算沿用 {@link InterviewContext} 默认值。
     * 当前不会恢复 {@code lastEvaluationSeq}，它保持上下文默认值 0。该方法只修改并返回上下文，
     * 不保存消息或面试实体。</p>
     */
    public InterviewContext initialize(Interview interview, UserProfileData userProfile,
                                       PositionProfileData positionProfile) {
        return AgentContext.runAs(AgentType.COORDINATOR, () -> {
            // 1. 画像使用本场快照；岗位大类单独读取当前岗位记录，供题库分层使用。
            InterviewContext context = new InterviewContext();
            context.setInterviewId(interview.getId());
            context.setUserId(interview.getUserId());
            context.setUserProfile(userProfile);
            context.setPositionProfile(positionProfile);
            context.setJobCategory(resolveJobCategory(interview.getPositionId()));
            context.setSelectedPhases(parsePhases(interview.getSelectedPhases()));

            // 2. 恢复环节、主题及业务计数；lastEvaluationSeq 当前未恢复，仍为默认值 0。
            context.setCurrentPhase(interview.getCurrentPhase());
            context.setCurrentTopicId(interview.getCurrentTopicId());
            context.setCurrentTopicName(interview.getCurrentTopicName());
            context.setCurrentDepth(interview.getCurrentDepth());
            context.setCurrentTopicFollowUpCount(interview.getCurrentTopicFollowUpCount());
            context.setConsecutiveFailures(interview.getConsecutiveFailures());
            context.setConsecutiveExcellence(interview.getConsecutiveExcellence());
            context.setTotalQuestionCount(interview.getTotalQuestionCount());
            context.setCurrentPhaseQuestionCount(interview.getCurrentPhaseQuestionCount());
            context.setSelfIntroQuestionCount(interview.getSelfIntroQuestionCount());
            context.setCurrentProjectIndex(interview.getCurrentProjectIndex());
            context.setCurrentBehavioralIndex(interview.getCurrentBehavioralIndex());

            // 3. 全局预算固定为 30、单主题追问上限固定为 5；各环节预算继续使用上下文默认值。
            // totalQuestionCount 当前由上游按消息条数累加，后续的 30 上限也按该字段判断。
            context.setMaxQuestions(30);
            context.setMaxFollowUpPerTopic(5);
            return context;
        });
    }

    /**
     * 由当前环节的 Skill 生成首题文本。
     *
     * <p>该方法不负责保存，首题由 {@code InterviewService} 持久化；但具体 Skill 在生成前可能初始化或重置
     * 当前主题、深度、项目索引、行为场景索引等上下文字段，因此调用后需要继续使用同一个
     * {@link InterviewContext}。</p>
     */
    public String generateFirstQuestion(InterviewContext context) {
        return AgentContext.runAs(AgentType.COORDINATOR, () -> {
            InterviewSkill skill = skillRegistry.resolve(context.getCurrentPhase());
            return skill.generateOpeningQuestion(context);
        });
    }

    /**
     * 处理一轮回答并生成、保存下一题。
     *
     * <p>固定顺序为：保存候选人回答，加载当前环节 Skill，根据 Skill 要求执行评估，
     * 评估器无结果时改用本地规则，随后由 Skill 决定继续追问、切换主题、进入下一环节或结束面试，
     * 最后保存生成的下一题。该过程会修改上下文并保存两条消息，但不保存 {@code Interview} 实体；
     * 新环节、主题、深度和评估信号通过 {@code TurnResult} 返回给 {@code InterviewService}。</p>
     */
    @Transactional
    public TurnResult coordinate(InterviewContext context, Interview interview,
                                 String questionText, String answerText) {
        return AgentContext.runAs(AgentType.COORDINATOR, () -> {
            // 1. 先保存候选人回答，后续评估与题目序号都以这条消息为基准。
            int answerSeqNo = interview.getTotalQuestionCount() + 1;
            InterviewMessage answer = saveMessage(interview, context.getCurrentPhase(),
                    "candidate", answerText, context.getCurrentTopicId(),
                    context.getCurrentTopicName(), context.getCurrentDepth(), answerSeqNo);

            // 2. 按当前环节加载 Skill，由它提供本环节的评估、计数和切换规则。
            InterviewSkill currentSkill = skillRegistry.resolve(context.getCurrentPhase());

            // 3. Skill 要求评估时调用评估器；无结果则使用本地规则，并更新计数及 lastEvaluationSeq。
            // 不需要评估时使用保留当前深度的中性信号，上述计数和评估序号均保持不变。
            EvaluationSignal signal;
            if (currentSkill.needEvaluate(context, answer)) {
                EvaluationResult evalResult = evaluatorAgent.evaluate(context, questionText, answerText);
                if (evalResult == null) {
                    evalResult = fallbackTool.ruleEvaluate(questionText, answerText);
                    signal = fallbackTool.extractSignal(evalResult);
                } else {
                    signal = currentSkill.extractSignal(evalResult);
                }
                currentSkill.updateCounters(context, signal);
                context.setLastEvaluationSeq(answer.getSeqNo());
            } else {
                signal = neutralSignal(context);
            }

            // 4. Skill 根据评估信号决定追问、换主题、换环节或收尾，并生成对应的下一题。
            NextAction action = currentSkill.decideNextAction(context, signal);
            log.info("[CoordinatorAgent] interviewId={}, action={}, phase={}",
                    interview.getId(), action, context.getCurrentPhase());

            String nextQuestion;
            InterviewPhase previousPhase = null;

            switch (action) {
                case FOLLOW_UP -> nextQuestion = currentSkill.generateNextQuestion(
                        context, questionText, answerText, signal);
                case SWITCH_TOPIC -> {
                    // switchToNextTopic 成功时已经修改上下文；当前相关 Skill 的过渡题方法还会再次推进，
                    // 因此该分支的现有行为可能连续跨过两个主题、项目或场景。
                    if (currentSkill.switchToNextTopic(context)) {
                        nextQuestion = currentSkill.generateTransitionQuestion(context);
                    } else {
                        previousPhase = context.getCurrentPhase();
                        advancePhase(context);
                        InterviewSkill nextSkill = skillRegistry.resolve(context.getCurrentPhase());
                        nextQuestion = nextSkill.generateOpeningQuestion(context);
                    }
                }
                case NEXT_PHASE -> {
                    previousPhase = context.getCurrentPhase();
                    advancePhase(context);
                    InterviewSkill nextSkill = skillRegistry.resolve(context.getCurrentPhase());
                    nextQuestion = nextSkill.generateOpeningQuestion(context);
                }
                case END_INTERVIEW -> {
                    previousPhase = context.getCurrentPhase();
                    context.setCurrentPhase(InterviewPhase.ENDING);
                    InterviewSkill endingSkill = skillRegistry.resolve(InterviewPhase.ENDING);
                    nextQuestion = endingSkill.generateOpeningQuestion(context);
                }
                default -> throw new IllegalStateException("未知决策: " + action);
            }

            // 5. 保存下一题；面试实体的环节和计数由上游 Service 随后统一同步。
            int nextQuestionSeqNo = answerSeqNo + 1;
            saveMessage(interview, context.getCurrentPhase(), "interviewer",
                    nextQuestion, context.getCurrentTopicId(), context.getCurrentTopicName(),
                    context.getCurrentDepth(), nextQuestionSeqNo);

            TurnResult result = new TurnResult();
            result.setQuestion(nextQuestion);
            result.setPhase(context.getCurrentPhase());
            result.setPreviousPhase(previousPhase);
            result.setTopicId(context.getCurrentTopicId());
            result.setTopicName(context.getCurrentTopicName());
            result.setDepth(context.getCurrentDepth());
            result.setSignal(signal);
            return result;
        });
    }

    /**
     * 修改传入面试实体的结束状态和结束时间。
     *
     * <p>{@code interrupted} 为 {@code true} 时标记为已中断，否则标记为正常结束。该方法不保存实体，
     * 也不释放简历或岗位占用；当前主流程只在用户主动结束面试时传入 {@code true}，保存和解锁由上游完成。</p>
     */
    public void endInterview(Interview interview, boolean interrupted) {
        AgentContext.runAs(AgentType.COORDINATOR, () -> {
            interview.setStatus(interrupted ? InterviewStatus.INTERRUPTED : InterviewStatus.ENDED);
            interview.setEndedAt(java.time.LocalDateTime.now());
        });
    }

    /**
     * 按已选择环节推进上下文。
     *
     * <p>存在下一环节时切换环节并清零当前环节题数；没有下一环节时直接进入结束环节，
     * 此时不会在本方法中重置题数、主题或深度等其他字段。</p>
     */
    private void advancePhase(InterviewContext context) {
        InterviewPhase next = context.nextPhase();
        if (next == null) {
            context.setCurrentPhase(InterviewPhase.ENDING);
        } else {
            context.setCurrentPhase(next);
            context.setCurrentPhaseQuestionCount(0);
        }
    }

    /**
     * 为无需评估的回答生成中性信号，保持当前深度并允许流程继续。
     * 该信号不会触发连续优秀、连续失败计数或上次评估序号的更新。
     */
    private EvaluationSignal neutralSignal(InterviewContext context) {
        EvaluationSignal signal = new EvaluationSignal();
        signal.setSuggestedNextDepth(context.getCurrentDepth());
        signal.setContinueProbing(true);
        return signal;
    }

    private InterviewMessage saveMessage(Interview interview, InterviewPhase phase, String role,
                                         String content, String topicId, String topicName, Integer depth,
                                         int seqNo) {
        InterviewMessage message = new InterviewMessage();
        message.setInterviewId(interview.getId());
        message.setPhase(phase.name());
        message.setRole(role);
        message.setContent(content);
        message.setTopicId(topicId);
        message.setTopicName(topicName);
        message.setDepth(depth);
        message.setSeqNo(seqNo);
        messageRepository.save(message);
        return message;
    }

    /**
     * 从当前岗位记录读取题库分层所需的岗位大类。
     * 岗位编号为空、记录不存在或类别为 {@code null} 时返回 {@code GENERAL}；空字符串按原值返回。
     */
    private String resolveJobCategory(Long positionId) {
        if (positionId == null) {
            return "GENERAL";
        }
        return positionRepository.findById(positionId)
                .map(p -> p.getJobCategory() == null ? "GENERAL" : p.getJobCategory())
                .orElse("GENERAL");
    }

    /**
     * 解析并按环节定义顺序排列本场环节。
     *
     * <p>合法 JSON 不额外去重；空文本返回空列表。格式错误或包含未知枚举时，返回包含自我介绍、
     * 专业面试、简历探讨、行为面试和结束环节的完整默认列表。</p>
     */
    private List<InterviewPhase> parsePhases(String phasesJson) {
        if (phasesJson == null || phasesJson.isBlank()) {
            return new ArrayList<>();
        }
        try {
            List<String> names = objectMapper.readValue(phasesJson, objectMapper.getTypeFactory()
                    .constructCollectionType(List.class, String.class));
            return names.stream()
                    .map(InterviewPhase::valueOf)
                    .sorted(java.util.Comparator.comparingInt(InterviewPhase::getOrder))
                    .toList();
        } catch (Exception e) {
            log.warn("[CoordinatorAgent] 解析环节列表失败: {}", e.getMessage());
            return new ArrayList<>(Arrays.asList(InterviewPhase.SELF_INTRO, InterviewPhase.PROFESSIONAL,
                    InterviewPhase.RESUME_DISCUSSION, InterviewPhase.BEHAVIORAL, InterviewPhase.ENDING));
        }
    }

    /**
     * 单轮协调结果。{@code previousPhase} 仅在跨环节或进入结束环节时有值，
     * 上游据此判断是否重置当前环节题数。
     */
    @lombok.Data
    public static class TurnResult {
        private String question;
        private InterviewPhase phase;
        private InterviewPhase previousPhase;
        private String topicId;
        private String topicName;
        private Integer depth;
        private EvaluationSignal signal;
    }
}
