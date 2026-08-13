package com.interviewcoach.interview.domain.agent.skill;

import com.interviewcoach.interview.domain.entity.InterviewMessage;
import com.interviewcoach.interview.domain.entity.InterviewPhase;
import com.interviewcoach.interview.domain.model.EvaluationResult;
import com.interviewcoach.interview.domain.model.EvaluationSignal;
import com.interviewcoach.interview.domain.model.InterviewContext;
import com.interviewcoach.interview.domain.model.NextAction;
import org.springframework.stereotype.Component;

/**
 * 自我介绍环节的固定单题策略。
 *
 * <p>进入环节只生成一道引导题；收到回答后不评估、不追问、不读取
 * {@code maxSelfIntroQuestions}，直接进入下一已选环节或结束。上下文中的默认值 3 当前没有
 * 本流程消费者，不能据此理解为会提三题。</p>
 */
@Component
public class SelfIntroSkill extends AbstractInterviewSkill {

    /** 将本实现注册为自我介绍环节 Skill。 */
    public SelfIntroSkill() {
        super(InterviewPhase.SELF_INTRO);
    }

    @Override
    /** 将自我介绍计数归零并生成唯一引导题。 */
    public String generateOpeningQuestion(InterviewContext context) {
        context.setSelfIntroQuestionCount(0);
        return interviewerAgent.generateSelfIntroQuestion(context);
    }

    @Override
    /** 固定返回不评估；回答不会影响后续专业环节的质量计数。 */
    public boolean needEvaluate(InterviewContext context, InterviewMessage answer) {
        // 自我介绍只有 1 题，不评估回答质量
        return false;
    }

    @Override
    /** 正常流程不会调用；若被调用则返回不带评分事件的继续信号。 */
    public EvaluationSignal extractSignal(EvaluationResult result) {
        // 本环节不评估，方法不会被调用
        EvaluationSignal signal = new EvaluationSignal();
        signal.setContinueProbing(true);
        return signal;
    }

    @Override
    /** 不更新连续优秀或困难计数，防止自我介绍污染后续专业流程。 */
    public void updateCounters(InterviewContext context, EvaluationSignal signal) {
        // 自我介绍不累计连续优秀/失败，避免影响后续专业面试
    }

    @Override
    /** 唯一回答完成后直接选择下一环节或结束，不检查题目预算。 */
    public NextAction decideNextAction(InterviewContext context, EvaluationSignal signal) {
        // 自我介绍只有 1 题，回答后直接切换至下一环节
        return nextPhaseOrEnd(context);
    }

    @Override
    /** 本单题策略不允许生成下一题；异常调用显式失败以暴露流程错误。 */
    public String generateNextQuestion(InterviewContext context, String previousQuestion,
                                       String previousAnswer, EvaluationSignal signal) {
        // 自我介绍只有 1 题，该方法不应被调用
        throw new IllegalStateException("自我介绍环节只有 1 题，不应生成下一题");
    }
}
