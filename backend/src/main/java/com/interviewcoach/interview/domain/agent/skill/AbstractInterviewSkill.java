package com.interviewcoach.interview.domain.agent.skill;

import com.interviewcoach.interview.domain.entity.InterviewPhase;
import com.interviewcoach.interview.domain.model.EvaluationResult;
import com.interviewcoach.interview.domain.model.EvaluationSignal;
import com.interviewcoach.interview.domain.model.InterviewContext;
import com.interviewcoach.interview.domain.model.NextAction;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Skill 抽象基类，封装通用评估信号提取和计数器更新逻辑。
 */
public abstract class AbstractInterviewSkill implements InterviewSkill {

    @Autowired
    protected com.interviewcoach.interview.domain.agent.InterviewerAgent interviewerAgent;

    private final InterviewPhase supportedPhase;

    protected AbstractInterviewSkill(InterviewPhase supportedPhase) {
        this.supportedPhase = supportedPhase;
    }

    @Override
    public boolean supports(InterviewPhase phase) {
        return supportedPhase == phase;
    }

    @Override
    public EvaluationSignal extractSignal(EvaluationResult result) {
        EvaluationSignal signal = new EvaluationSignal();
        if (result == null) {
            signal.setSuggestedNextDepth(1);
            signal.setContinueProbing(true);
            return signal;
        }
        signal.setSuggestedNextDepth(clamp(result.getSuggestedNextDepth(), 1, 5));
        int avg = average(result);
        if (avg >= 85) {
            signal.setKeyEventType("EXCELLENT");
        } else if (avg < 55) {
            signal.setKeyEventType("STRUGGLED");
        }
        signal.setContinueProbing(avg >= 55);
        signal.setBriefComment(result.getComment());
        return signal;
    }

    @Override
    public void updateCounters(InterviewContext context, EvaluationSignal signal) {
        String event = signal.getKeyEventType();
        if ("EXCELLENT".equalsIgnoreCase(event)) {
            context.setConsecutiveExcellence(context.getConsecutiveExcellence() + 1);
            context.setConsecutiveFailures(0);
        } else if ("STRUGGLED".equalsIgnoreCase(event)) {
            context.setConsecutiveFailures(context.getConsecutiveFailures() + 1);
            context.setConsecutiveExcellence(0);
        } else {
            context.setConsecutiveFailures(0);
            context.setConsecutiveExcellence(0);
        }
    }

    /**
     * 默认结束策略：当前为最后一环节则结束面试，否则进入下一环节。
     */
    protected NextAction nextPhaseOrEnd(InterviewContext context) {
        if (context.isLastPhase() || context.getCurrentPhase() == InterviewPhase.ENDING) {
            return NextAction.END_INTERVIEW;
        }
        return NextAction.NEXT_PHASE;
    }

    protected int average(EvaluationResult r) {
        int sum = 0;
        int count = 0;
        if (r.getTechnicalDepth() != null) { sum += r.getTechnicalDepth(); count++; }
        if (r.getTechnicalBreadth() != null) { sum += r.getTechnicalBreadth(); count++; }
        if (r.getPracticalExperience() != null) { sum += r.getPracticalExperience(); count++; }
        if (r.getExpression() != null) { sum += r.getExpression(); count++; }
        if (r.getLearningAbility() != null) { sum += r.getLearningAbility(); count++; }
        return count == 0 ? 70 : sum / count;
    }

    protected int clamp(Integer value, int min, int max) {
        if (value == null) return min;
        return Math.max(min, Math.min(max, value));
    }
}
