package com.interviewcoach.interview.domain.agent.skill;

import com.interviewcoach.interview.domain.entity.InterviewPhase;
import com.interviewcoach.interview.domain.model.EvaluationResult;
import com.interviewcoach.interview.domain.model.EvaluationSignal;
import com.interviewcoach.interview.domain.model.InterviewContext;
import com.interviewcoach.interview.domain.model.NextAction;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Skill 抽象基类，负责把已校验分数转换成服务端评估事件，并维护连续优秀或困难计数。
 * 模型不能直接提供事件、下一题深度或流程动作。
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

    /**
     * 根据五项分数的平均值生成固定事件：85 分及以上为优秀，低于 55 分为困难，其余为中性。
     * {@code result} 为空时返回继续追问的中性信号，不从缺失结果中猜测分数。
     */
    @Override
    public EvaluationSignal extractSignal(EvaluationResult result) {
        EvaluationSignal signal = new EvaluationSignal();
        if (result == null) {
            signal.setContinueProbing(true);
            return signal;
        }
        int avg = average(result);
        // 事件名称和阈值由服务端固定，模型评价文本即使包含类似字样也不会改变事件。
        if (avg >= 85) {
            signal.setKeyEventType("EXCELLENT");
        } else if (avg < 55) {
            signal.setKeyEventType("STRUGGLED");
        }
        signal.setContinueProbing(avg >= 55);
        signal.setBriefComment(result.getComment());
        return signal;
    }

    /**
     * 只根据服务端派生事件更新连续计数；中性事件会同时清零两种连续状态。
     * 主流程仅在评估结果合法时调用本方法，因此评估失败不会改动这些计数。
     */
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

    /**
     * 只对非空分数求整数平均值。正常安全评估包含全部五项；若其他调用方传入全空结果，沿用默认 70 分。
     */
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

}
