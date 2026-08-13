package com.interviewcoach.interview.domain.agent.skill;

import com.interviewcoach.interview.domain.entity.InterviewMessage;
import com.interviewcoach.interview.domain.entity.InterviewPhase;
import com.interviewcoach.interview.domain.model.EvaluationResult;
import com.interviewcoach.interview.domain.model.EvaluationSignal;
import com.interviewcoach.interview.domain.model.InterviewContext;
import com.interviewcoach.interview.domain.model.NextAction;
import org.springframework.stereotype.Component;

/**
 * 结束环节的终止策略。
 *
 * <p>进入该环节时只生成结束语，不评估回答、不更新质量计数，并始终返回结束动作。正常协调
 * 流程不会继续追问；保留的下一题方法仅在异常调用时再次生成结束语，不改变上下文。</p>
 */
@Component
public class EndingSkill extends AbstractInterviewSkill {

    /** 将本实现注册为结束环节 Skill。 */
    public EndingSkill() {
        super(InterviewPhase.ENDING);
    }

    @Override
    /** 由面试官安全出题入口生成结束语，失败时返回固定结束模板。 */
    public String generateOpeningQuestion(InterviewContext context) {
        return interviewerAgent.generateEndingMessage(context);
    }

    @Override
    /** 结束环节的任何输入都不触发回答评估。 */
    public boolean needEvaluate(InterviewContext context, InterviewMessage answer) {
        return false;
    }

    @Override
    /** 忽略传入评估结果并返回不继续探测的固定信号。 */
    public EvaluationSignal extractSignal(EvaluationResult result) {
        EvaluationSignal signal = new EvaluationSignal();
        signal.setContinueProbing(false);
        return signal;
    }

    @Override
    /** 结束环节不修改连续质量或其他上下文计数。 */
    public void updateCounters(InterviewContext context, EvaluationSignal signal) {
        // 结束环节不更新计数器
    }

    @Override
    /** 无条件返回结束面试动作。 */
    public NextAction decideNextAction(InterviewContext context, EvaluationSignal signal) {
        return NextAction.END_INTERVIEW;
    }

    @Override
    /** 异常调用时仍只返回结束语，不生成新的业务问题或推进状态。 */
    public String generateNextQuestion(InterviewContext context, String previousQuestion,
                                       String previousAnswer, EvaluationSignal signal) {
        return interviewerAgent.generateEndingMessage(context);
    }
}
