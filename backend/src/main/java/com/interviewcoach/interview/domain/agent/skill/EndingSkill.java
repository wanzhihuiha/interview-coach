package com.interviewcoach.interview.domain.agent.skill;

import com.interviewcoach.interview.domain.entity.InterviewMessage;
import com.interviewcoach.interview.domain.entity.InterviewPhase;
import com.interviewcoach.interview.domain.model.EvaluationResult;
import com.interviewcoach.interview.domain.model.EvaluationSignal;
import com.interviewcoach.interview.domain.model.InterviewContext;
import com.interviewcoach.interview.domain.model.NextAction;
import org.springframework.stereotype.Component;

/**
 * 结束环节 Skill：生成结束语，不再评估和追问。
 */
@Component
public class EndingSkill extends AbstractInterviewSkill {

    public EndingSkill() {
        super(InterviewPhase.ENDING);
    }

    @Override
    public String generateOpeningQuestion(InterviewContext context) {
        return interviewerAgent.generateEndingMessage(context);
    }

    @Override
    public boolean needEvaluate(InterviewContext context, InterviewMessage answer) {
        return false;
    }

    @Override
    public EvaluationSignal extractSignal(EvaluationResult result) {
        EvaluationSignal signal = new EvaluationSignal();
        signal.setContinueProbing(false);
        return signal;
    }

    @Override
    public void updateCounters(InterviewContext context, EvaluationSignal signal) {
        // 结束环节不更新计数器
    }

    @Override
    public NextAction decideNextAction(InterviewContext context, EvaluationSignal signal) {
        return NextAction.END_INTERVIEW;
    }

    @Override
    public String generateNextQuestion(InterviewContext context, String previousQuestion,
                                       String previousAnswer, EvaluationSignal signal) {
        return interviewerAgent.generateEndingMessage(context);
    }
}
