package com.interviewcoach.interview.domain.agent.skill;

import com.interviewcoach.interview.domain.entity.InterviewMessage;
import com.interviewcoach.interview.domain.entity.InterviewPhase;
import com.interviewcoach.interview.domain.model.EvaluationSignal;
import com.interviewcoach.interview.domain.model.InterviewContext;
import com.interviewcoach.interview.domain.model.NextAction;
import com.interviewcoach.position.domain.model.PositionProfileData;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 专业面试 Skill：多主题递进式提问。
 * 每个主题下根据评估信号在 L1-L5 之间切换深度，主题内追问数耗尽或深度达到 L5 后切换主题；
 * 主题耗尽后进入下一环节。
 */
@Component
public class ProfessionalSkill extends AbstractInterviewSkill {

    public ProfessionalSkill() {
        super(InterviewPhase.PROFESSIONAL);
    }

    @Override
    public String generateOpeningQuestion(InterviewContext context) {
        initTopics(context);
        return interviewerAgent.generateProfessionalQuestion(context, null, null, 1);
    }

    /**
     * 当前回答序号距离上次评估序号达到 2，或到达主题追问上限、连续优秀/困难阈值时，触发一次评估。
     */
    @Override
    public boolean needEvaluate(InterviewContext context, InterviewMessage answer) {
        int seq = answer.getSeqNo() == null ? 0 : answer.getSeqNo();
        int last = context.getLastEvaluationSeq() == null ? 0 : context.getLastEvaluationSeq();
        int distance = seq - last;
        return distance >= 2
                || context.getCurrentTopicFollowUpCount() >= context.getMaxFollowUpPerTopic()
                || context.getConsecutiveFailures() >= 2
                || context.getConsecutiveExcellence() >= 2;
    }

    /**
     * 按服务端数量、深度和连续困难规则决定追问、换主题或换环节；不会读取模型提供的流程字段。
     */
    @Override
    public NextAction decideNextAction(InterviewContext context, EvaluationSignal signal) {
        if (Boolean.TRUE.equals(signal.getShouldSwitchTopic())) {
            return hasNextTopic(context) ? NextAction.SWITCH_TOPIC : NextAction.NEXT_PHASE;
        }
        if (context.getConsecutiveFailures() >= 2 && context.getCurrentDepth() <= 1) {
            return hasNextTopic(context) ? NextAction.SWITCH_TOPIC : NextAction.NEXT_PHASE;
        }
        if (context.getCurrentTopicFollowUpCount() >= context.getMaxFollowUpPerTopic()) {
            return hasNextTopic(context) ? NextAction.SWITCH_TOPIC : NextAction.NEXT_PHASE;
        }
        if (context.getCurrentDepth() >= 5) {
            return hasNextTopic(context) ? NextAction.SWITCH_TOPIC : NextAction.NEXT_PHASE;
        }
        if (context.getTotalQuestionCount() >= context.getMaxQuestions()) {
            return nextPhaseOrEnd(context);
        }
        return NextAction.FOLLOW_UP;
    }

    /**
     * 生成同一主题的下一题。优秀事件升一级，困难事件降一级，中性或评估失败保持当前深度。
     */
    @Override
    public String generateNextQuestion(InterviewContext context, String previousQuestion,
                                       String previousAnswer, EvaluationSignal signal) {
        int currentDepth = context.getCurrentDepth() == null ? 1 : context.getCurrentDepth();
        // 深度调整只使用服务端从已校验分数得到的固定事件，不接收模型建议的目标深度。
        int targetDepth = currentDepth;
        if ("EXCELLENT".equalsIgnoreCase(signal.getKeyEventType())) {
            targetDepth = Math.min(5, currentDepth + 1);
        } else if ("STRUGGLED".equalsIgnoreCase(signal.getKeyEventType())) {
            targetDepth = Math.max(1, currentDepth - 1);
        }
        context.setCurrentDepth(targetDepth);
        context.setCurrentTopicFollowUpCount(context.getCurrentTopicFollowUpCount() + 1);
        return interviewerAgent.generateProfessionalQuestion(context, previousQuestion, previousAnswer, targetDepth);
    }

    @Override
    public String generateTransitionQuestion(InterviewContext context) {
        switchToNextTopic(context);
        return interviewerAgent.generateTopicTransition(context,
                context.getCurrentTopicName(), context.getCurrentTopicId());
    }

    /**
     * 切换到岗位分析给出的下一个探查主题，并重置新主题的深度、追问数和连续质量计数。
     */
    @Override
    public boolean switchToNextTopic(InterviewContext context) {
        List<PositionProfileData.ProbingDirection> directions = getDirections(context);
        if (directions == null || directions.isEmpty()) {
            return false;
        }
        int currentIndex = -1;
        for (int i = 0; i < directions.size(); i++) {
            if (directions.get(i).getDirection().equals(context.getCurrentTopicName())) {
                currentIndex = i;
                break;
            }
        }
        int nextIndex = currentIndex + 1;
        if (nextIndex >= directions.size()) {
            return false;
        }
        PositionProfileData.ProbingDirection next = directions.get(nextIndex);
        context.setCurrentTopicId("topic-" + nextIndex + "-" + next.getDirection());
        context.setCurrentTopicName(next.getDirection());
        context.setCurrentDepth(1);
        context.setCurrentTopicFollowUpCount(0);
        context.setConsecutiveFailures(0);
        context.setConsecutiveExcellence(0);
        return true;
    }

    /** 使用岗位探查方向初始化首个主题；没有方向时使用不带岗位推断的“综合能力”主题。 */
    private void initTopics(InterviewContext context) {
        List<PositionProfileData.ProbingDirection> directions = getDirections(context);
        if (directions == null || directions.isEmpty()) {
            context.setCurrentTopicId("general");
            context.setCurrentTopicName("综合能力");
            return;
        }
        PositionProfileData.ProbingDirection first = directions.get(0);
        context.setCurrentTopicId("topic-0-" + first.getDirection());
        context.setCurrentTopicName(first.getDirection());
        context.setCurrentDepth(1);
        context.setCurrentTopicFollowUpCount(0);
    }

    private boolean hasNextTopic(InterviewContext context) {
        List<PositionProfileData.ProbingDirection> directions = getDirections(context);
        if (directions == null || directions.isEmpty()) {
            return false;
        }
        int currentIndex = -1;
        for (int i = 0; i < directions.size(); i++) {
            if (directions.get(i).getDirection().equals(context.getCurrentTopicName())) {
                currentIndex = i;
                break;
            }
        }
        return currentIndex + 1 < directions.size();
    }

    private List<PositionProfileData.ProbingDirection> getDirections(InterviewContext context) {
        if (context.getPositionProfile() == null) {
            return null;
        }
        return context.getPositionProfile().getProbingDirections();
    }
}
