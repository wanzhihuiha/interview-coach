package com.interviewcoach.interview.domain.agent.skill;

import com.interviewcoach.interview.domain.entity.InterviewMessage;
import com.interviewcoach.interview.domain.entity.InterviewPhase;
import com.interviewcoach.interview.domain.model.EvaluationResult;
import com.interviewcoach.interview.domain.model.EvaluationSignal;
import com.interviewcoach.interview.domain.model.InterviewContext;
import com.interviewcoach.interview.domain.model.NextAction;
import org.springframework.stereotype.Component;

/**
 * 行为面试 Skill：以场景为主题，每个场景 1-2 题，场景耗尽后进入下一环节。
 */
@Component
public class BehavioralSkill extends AbstractInterviewSkill {

    private static final String[] SCENARIOS = {
            "团队协作", "问题解决", "成长学习", "领导力", "沟通表达"
    };

    public BehavioralSkill() {
        super(InterviewPhase.BEHAVIORAL);
    }

    @Override
    public String generateOpeningQuestion(InterviewContext context) {
        context.setCurrentBehavioralIndex(0);
        return interviewerAgent.generateBehavioralQuestion(context);
    }

    @Override
    public boolean needEvaluate(InterviewContext context, InterviewMessage answer) {
        int seq = answer.getSeqNo() == null ? 0 : answer.getSeqNo();
        int last = context.getLastEvaluationSeq() == null ? 0 : context.getLastEvaluationSeq();
        return seq - last >= 2;
    }

    @Override
    public EvaluationSignal extractSignal(EvaluationResult result) {
        EvaluationSignal signal = new EvaluationSignal();
        if (result == null) {
            signal.setContinueProbing(true);
            return signal;
        }
        int avg = average(result);
        if (avg >= 85) {
            signal.setKeyEventType("EXCELLENT");
        } else if (avg < 55) {
            signal.setKeyEventType("STRUGGLED");
        }
        signal.setContinueProbing(true);
        signal.setBriefComment(result.getComment());
        return signal;
    }

    @Override
    public NextAction decideNextAction(InterviewContext context, EvaluationSignal signal) {
        int maxBehavioral = context.getMaxBehavioralQuestions() != null
                ? context.getMaxBehavioralQuestions() : SCENARIOS.length;
        if (context.getCurrentBehavioralIndex() + 1 >= maxBehavioral) {
            return nextPhaseOrEnd(context);
        }
        return NextAction.FOLLOW_UP;
    }

    @Override
    public String generateNextQuestion(InterviewContext context, String previousQuestion,
                                       String previousAnswer, EvaluationSignal signal) {
        context.setCurrentBehavioralIndex(context.getCurrentBehavioralIndex() + 1);
        return interviewerAgent.generateBehavioralQuestion(context);
    }

    /**
     * 行为面试的"主题"即场景，每题切换一个场景。
     */
    @Override
    public String generateTransitionQuestion(InterviewContext context) {
        context.setCurrentBehavioralIndex(context.getCurrentBehavioralIndex() + 1);
        return interviewerAgent.generateBehavioralQuestion(context);
    }

    @Override
    public boolean switchToNextTopic(InterviewContext context) {
        if (context.getCurrentBehavioralIndex() + 1 >= SCENARIOS.length) {
            return false;
        }
        context.setCurrentBehavioralIndex(context.getCurrentBehavioralIndex() + 1);
        return true;
    }
}
