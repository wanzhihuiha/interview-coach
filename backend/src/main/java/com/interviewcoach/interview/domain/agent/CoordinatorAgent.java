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
import com.interviewcoach.common.security.agent.AgentContext;
import com.interviewcoach.common.security.agent.AgentType;
import com.interviewcoach.position.domain.model.PositionProfileData;
import com.interviewcoach.resume.domain.model.UserProfileData;
import com.interviewcoach.resume.domain.model.ResumeProfileAnalysisData;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 协调者 Agent：负责初始化上下文、识别当前环节意图、调度对应 Skill。
 * 各环节的提问策略、评估策略、切换策略由具体 Skill 实现。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CoordinatorAgent {

    private final EvaluatorAgent evaluatorAgent;
    private final SkillRegistry skillRegistry;
    private final ObjectMapper objectMapper;

    /**
     * 从面试快照初始化运行上下文；辅助分析随上下文传递，但不进入评估器和评分流程。
     */
    public InterviewContext initialize(Interview interview, UserProfileData userProfile,
                                       ResumeProfileAnalysisData userProfileAnalysis,
                                       PositionProfileData positionProfile) {
        return AgentContext.runAs(AgentType.COORDINATOR, () -> {
            InterviewContext context = new InterviewContext();
            context.setInterviewId(interview.getId());
            context.setUserId(interview.getUserId());
            context.setUserProfile(userProfile);
            context.setUserProfileAnalysis(userProfileAnalysis);
            context.setPositionProfile(positionProfile);
            context.setJobCategory(interview.getJobCategorySnapshot());
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
     * 在事务外处理用户回答并生成下一题；消息和面试状态由应用层短事务统一写回。
     * 模型只提供经过校验的分数和评价，质量事件、计数器和下一步动作全部由服务端计算。
     */
    public TurnResult coordinate(InterviewContext context, Interview interview,
                                 String questionText, String answerText) {
        return AgentContext.runAs(AgentType.COORDINATOR, () -> {
            int answerSeqNo = interview.getTotalQuestionCount() + 1;
            InterviewMessage answer = new InterviewMessage();
            answer.setInterviewId(interview.getId());
            answer.setPhase(context.getCurrentPhase().name());
            answer.setRole("candidate");
            answer.setContent(answerText);
            answer.setTopicId(context.getCurrentTopicId());
            answer.setTopicName(context.getCurrentTopicName());
            answer.setDepth(context.getCurrentDepth());
            answer.setSeqNo(answerSeqNo);

            InterviewSkill currentSkill = skillRegistry.resolve(context.getCurrentPhase());

            EvaluationSignal signal;
            if (currentSkill.needEvaluate(context, answer)) {
                EvaluationResult evalResult = evaluatorAgent.evaluate(context, questionText, answerText);
                if (evalResult == null) {
                    // 评估不可用时不猜分、不改变质量计数，只让服务端原有数量规则继续推进。
                    signal = neutralSignal();
                } else {
                    // Skill 只根据五项合法分数生成固定事件，再由该事件更新服务端计数器。
                    signal = currentSkill.extractSignal(evalResult);
                    currentSkill.updateCounters(context, signal);
                }
                // 记录本轮已经尝试过评估，避免评估失败后反复对同一回答发起模型调用。
                context.setLastEvaluationSeq(answer.getSeqNo());
            } else {
                signal = neutralSignal();
            }

            // 下一步动作只读取服务端上下文和服务端派生信号，不接受模型返回 phase 或 nextAction。
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

    /** 切换到服务端选择的下一环节，并在进入新环节时重置该环节的题目计数。 */
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
     * 创建不含评分和流程建议的中性信号，供“不需要评估”和“评估不可用”两种情况继续执行数量规则。
     */
    private EvaluationSignal neutralSignal() {
        EvaluationSignal signal = new EvaluationSignal();
        signal.setContinueProbing(true);
        return signal;
    }

    /**
     * 解析面试快照中的环节列表；快照为空时返回空列表，内容损坏时回退到既有默认环节顺序。
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

    /** 协调完成一轮后交给应用层保存的服务端结果快照。 */
    @lombok.Data
    public static class TurnResult {

        /** 服务端策略生成的下一道问题或结束语。 */
        private String question;

        /** 生成下一道内容后所处的面试环节。 */
        private InterviewPhase phase;

        /** 本轮发生环节切换时记录切换前环节，未切换时为空。 */
        private InterviewPhase previousPhase;

        /** 下一轮使用的主题标识。 */
        private String topicId;

        /** 下一轮使用的主题名称。 */
        private String topicName;

        /** 下一道问题的服务端题目深度。 */
        private Integer depth;

        /** 本轮由服务端派生的精简评估信号；评估不可用时为中性信号。 */
        private EvaluationSignal signal;
    }
}
