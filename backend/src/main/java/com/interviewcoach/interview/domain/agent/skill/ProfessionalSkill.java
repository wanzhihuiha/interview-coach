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

    @Override
    public String generateNextQuestion(InterviewContext context, String previousQuestion,
                                       String previousAnswer, EvaluationSignal signal) {
        int targetDepth = signal.getSuggestedNextDepth() != null
                ? signal.getSuggestedNextDepth() : context.getCurrentDepth() + 1;
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
