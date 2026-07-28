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
 * 协调者 Agent：负责初始化上下文、识别当前环节意图、调度对应 Skill。
 * 各环节的提问策略、评估策略、切换策略由具体 Skill 实现。
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
     * 初始化面试上下文。
     */
    public InterviewContext initialize(Interview interview, UserProfileData userProfile,
                                       PositionProfileData positionProfile) {
        return AgentContext.runAs(AgentType.COORDINATOR, () -> {
            InterviewContext context = new InterviewContext();
            context.setInterviewId(interview.getId());
            context.setUserId(interview.getUserId());
            context.setUserProfile(userProfile);
            context.setPositionProfile(positionProfile);
            context.setJobCategory(resolveJobCategory(interview.getPositionId()));
            context.setSelectedPhases(parsePhases(interview.getSelectedPhases()));
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
            context.setMaxQuestions(30);
            context.setMaxFollowUpPerTopic(5);
            return context;
        });
    }

    /**
     * 生成首题。根据当前环节加载对应 Skill。
     */
    public String generateFirstQuestion(InterviewContext context) {
        return AgentContext.runAs(AgentType.COORDINATOR, () -> {
            InterviewSkill skill = skillRegistry.resolve(context.getCurrentPhase());
            return skill.generateOpeningQuestion(context);
        });
    }

    /**
     * 处理用户回答并生成下一题。
     * 流程：保存回答 -> 当前 Skill 评估 -> Skill 决策 -> 生成下一题/切换环节/结束。
     */
    @Transactional
    public TurnResult coordinate(InterviewContext context, Interview interview,
                                 String questionText, String answerText) {
        return AgentContext.runAs(AgentType.COORDINATOR, () -> {
            // 1. 保存回答
            int answerSeqNo = interview.getTotalQuestionCount() + 1;
            InterviewMessage answer = saveMessage(interview, context.getCurrentPhase(),
                    "candidate", answerText, context.getCurrentTopicId(),
                    context.getCurrentTopicName(), context.getCurrentDepth(), answerSeqNo);

            // 2. 加载当前 Skill
            InterviewSkill currentSkill = skillRegistry.resolve(context.getCurrentPhase());

            // 3. 是否评估
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

            // 4. Skill 决策
            NextAction action = currentSkill.decideNextAction(context, signal);
            log.info("[CoordinatorAgent] interviewId={}, action={}, phase={}",
                    interview.getId(), action, context.getCurrentPhase());

            String nextQuestion;
            InterviewPhase previousPhase = null;

            switch (action) {
                case FOLLOW_UP -> nextQuestion = currentSkill.generateNextQuestion(
                        context, questionText, answerText, signal);
                case SWITCH_TOPIC -> {
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

            // 5. 保存下一题
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
     * 结束面试。
     */
    public void endInterview(Interview interview, boolean interrupted) {
        AgentContext.runAs(AgentType.COORDINATOR, () -> {
            interview.setStatus(interrupted ? InterviewStatus.INTERRUPTED : InterviewStatus.ENDED);
            interview.setEndedAt(java.time.LocalDateTime.now());
        });
    }

    private void advancePhase(InterviewContext context) {
        InterviewPhase next = context.nextPhase();
        if (next == null) {
            context.setCurrentPhase(InterviewPhase.ENDING);
        } else {
            context.setCurrentPhase(next);
            context.setCurrentPhaseQuestionCount(0);
        }
    }

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

    private String resolveJobCategory(Long positionId) {
        if (positionId == null) {
            return "GENERAL";
        }
        return positionRepository.findById(positionId)
                .map(p -> p.getJobCategory() == null ? "GENERAL" : p.getJobCategory())
                .orElse("GENERAL");
    }

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
