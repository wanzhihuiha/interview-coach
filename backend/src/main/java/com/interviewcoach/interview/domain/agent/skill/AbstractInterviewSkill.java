package com.interviewcoach.interview.domain.agent.skill;

import com.interviewcoach.interview.domain.entity.InterviewPhase;
import com.interviewcoach.interview.domain.model.EvaluationResult;
import com.interviewcoach.interview.domain.model.EvaluationSignal;
import com.interviewcoach.interview.domain.model.InterviewContext;
import com.interviewcoach.interview.domain.model.NextAction;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 各面试环节 Skill 的公共服务端规则基类。
 *
 * <p>具体 Skill 由 Coordinator 调度；本类持有 Interviewer 用于出题，并把已校验的五项分数
 * 转为固定质量事件、维护连续优秀/困难计数及提供默认环节结束判断。模型不能直接提供事件、
 * 下一题深度或流程动作。</p>
 */
public abstract class AbstractInterviewSkill implements InterviewSkill {

    /** 具体 Skill 生成首题、追问或过渡题时调用的面试官 Agent。 */
    @Autowired
    protected com.interviewcoach.interview.domain.agent.InterviewerAgent interviewerAgent;

    /** 当前 Skill 唯一支持的服务端面试环节。 */
    private final InterviewPhase supportedPhase;

    /** 绑定实现与唯一环节，供注册表扫描。 */
    protected AbstractInterviewSkill(InterviewPhase supportedPhase) {
        this.supportedPhase = supportedPhase;
    }

    @Override
    /** 仅在输入环节与构造时绑定值相同时返回支持。 */
    public boolean supports(InterviewPhase phase) {
        return supportedPhase == phase;
    }

    /**
     * 根据五项分数的整数平均值生成固定事件：85 分及以上为优秀，低于 55 分为困难，其余为中性。
     * {@code result} 为空时返回继续追问的中性信号，不从缺失结果中猜测分数。
     * 85 和 55 的精确业务依据当前缺失，调整会改变深度与主题推进。
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
     * 默认结束策略：当前为已选列表最后一环节或已经在 ENDING 时结束，否则进入下一环节。
     */
    protected NextAction nextPhaseOrEnd(InterviewContext context) {
        if (context.isLastPhase() || context.getCurrentPhase() == InterviewPhase.ENDING) {
            return NextAction.END_INTERVIEW;
        }
        return NextAction.NEXT_PHASE;
    }

    /**
     * 只对非空分数求整数平均值。正常安全评估包含全部五项；若其他调用方传入全空结果，
     * 沿用当前固定 70 分中性默认值，其精确依据缺失。
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
