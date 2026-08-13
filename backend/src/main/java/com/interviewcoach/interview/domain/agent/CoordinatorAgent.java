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
 * 面试轮次的服务端协调者。
 *
 * <p>应用服务用创建快照初始化上下文后，由本组件解析当前环节并调度对应 Skill。评估模型
 * 只返回经过校验的分数和评价；质量信号、计数器、主题/环节切换及结束动作均由 Skill 与
 * 本协调者确定，最终结果交回应用层短事务保存。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CoordinatorAgent {

    /** 负责通过安全 LLM 入口评估当前问答；失败时返回空结果。 */
    private final EvaluatorAgent evaluatorAgent;

    /** 负责按当前面试环节解析唯一 Skill 实现。 */
    private final SkillRegistry skillRegistry;

    /** 负责把会话中保存的环节 JSON 快照还原为服务端枚举列表。 */
    private final ObjectMapper objectMapper;

    /**
     * 从面试实体和画像快照初始化运行上下文。
     *
     * <p>辅助分析只随上下文供出题器选择核验线索，不传入评估器或报告评分公式。最大问题数
     * 30 和单主题追问上限 5 是当前固定预算，精确产品或容量依据缺失。</p>
     */
    public InterviewContext initialize(Interview interview, UserProfileData userProfile,
                                       ResumeProfileAnalysisData userProfileAnalysis,
                                       PositionProfileData positionProfile) {
        // 在 COORDINATOR 权限上下文中组装服务端状态，供后续受限 Agent 调用链使用。
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

    /** 按当前环节解析 Skill 并生成首题；出题器内部负责题库、模型和固定模板降级。 */
    public String generateFirstQuestion(InterviewContext context) {
        return AgentContext.runAs(AgentType.COORDINATOR, () -> {
            // Skill 注册表是环节到策略的服务端边界，解析失败直接阻断初始化。
            InterviewSkill skill = skillRegistry.resolve(context.getCurrentPhase());
            return skill.generateOpeningQuestion(context);
        });
    }

    /**
     * 在事务外处理用户回答并生成下一题。
     *
     * <p>本方法只构造尚未持久化的候选人消息用于 Skill 判断；应用层随后在 reservation 仍匹配
     * 时统一保存回答、下一题和上下文。模型只提供校验后的分数与评价，质量事件、计数器和
     * 下一步动作全部由服务端计算。评估不可用时使用中性信号，不猜分也不更新质量计数。</p>
     */
    public TurnResult coordinate(InterviewContext context, Interview interview,
                                 String questionText, String answerText) {
        return AgentContext.runAs(AgentType.COORDINATOR, () -> {
            // 预构造本轮候选人消息，供 needEvaluate 判断和记录本轮评估序号；此处不写数据库。
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

            // 当前环节 Skill 负责评估频率、信号派生、计数更新和确定性动作选择。
            InterviewSkill currentSkill = skillRegistry.resolve(context.getCurrentPhase());

            EvaluationSignal signal;
            if (currentSkill.needEvaluate(context, answer)) {
                // 安全评估失败返回 null；失败不会把模型原文或伪造分数带入流程控制。
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
            // 下一步动作只读取服务端上下文和服务端派生信号，不接受模型返回流程字段。
            NextAction action = currentSkill.decideNextAction(context, signal);
            log.info("[CoordinatorAgent] interviewId={}, action={}, phase={}",
                    interview.getId(), action, context.getCurrentPhase());

            String nextQuestion;
            InterviewPhase previousPhase = null;

            switch (action) {
                // 同一主题继续追问，Skill 会更新自身计数并调用面试官生成对应题型。
                case FOLLOW_UP -> nextQuestion = currentSkill.generateNextQuestion(
                        context, questionText, answerText, signal);
                case SWITCH_TOPIC -> {
                    // 先尝试在当前环节换主题；无更多主题时才推进到用户选择的下一环节。
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
                    // Skill 明确要求切环节时记录旧环节，以便持久化层重置新环节题目计数。
                    previousPhase = context.getCurrentPhase();
                    advancePhase(context);
                    InterviewSkill nextSkill = skillRegistry.resolve(context.getCurrentPhase());
                    nextQuestion = nextSkill.generateOpeningQuestion(context);
                }
                case END_INTERVIEW -> {
                    // 结束动作统一进入 ENDING Skill，生成结束语后由应用层把会话置为正常结束。
                    previousPhase = context.getCurrentPhase();
                    context.setCurrentPhase(InterviewPhase.ENDING);
                    InterviewSkill endingSkill = skillRegistry.resolve(InterviewPhase.ENDING);
                    nextQuestion = endingSkill.generateOpeningQuestion(context);
                }
                default -> throw new IllegalStateException("未知决策: " + action);
            }

            // 返回下一题和推进后的状态快照，应用层验证 reservation 后再原子落库。
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
     * 在内存实体上设置面试终态和结束时间。
     * {@code interrupted=true} 用于用户主动结束，{@code false} 表示正常结束；持久化由调用方负责。
     */
    public void endInterview(Interview interview, boolean interrupted) {
        AgentContext.runAs(AgentType.COORDINATOR, () -> {
            interview.setStatus(interrupted ? InterviewStatus.INTERRUPTED : InterviewStatus.ENDED);
            interview.setEndedAt(java.time.LocalDateTime.now());
        });
    }

    /**
     * 切换到已选列表中的下一环节，并在找到下一环节时重置环节题目计数；列表耗尽则进入结束环节。
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
     * 创建不含评分和流程建议的中性信号。
     * {@code continueProbing=true} 只供现有 Skill 继续执行数量规则，不代表模型确认应继续追问。
     */
    private EvaluationSignal neutralSignal() {
        EvaluationSignal signal = new EvaluationSignal();
        signal.setContinueProbing(true);
        return signal;
    }

    /**
     * 解析并按固定 order 排序面试环节快照。
     * 空快照返回空列表；坏 JSON 或未知枚举值会记录告警并回退到固定五环节顺序。
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

    /** 协调完成一轮后交给应用层短事务保存的服务端结果快照。 */
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
