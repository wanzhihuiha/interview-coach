package com.interviewcoach.interview.domain.agent.skill;

import com.interviewcoach.interview.domain.entity.InterviewMessage;
import com.interviewcoach.interview.domain.entity.InterviewPhase;
import com.interviewcoach.interview.domain.model.EvaluationResult;
import com.interviewcoach.interview.domain.model.EvaluationSignal;
import com.interviewcoach.interview.domain.model.InterviewContext;
import com.interviewcoach.interview.domain.model.NextAction;
import org.springframework.stereotype.Component;

/**
 * 自我介绍 Skill：单一主题，固定数量问题后进入下一环节。
 * 问题数量可通过上下文配置扩展，默认 3 题后切换。
 */
@Component
public class SelfIntroSkill extends AbstractInterviewSkill {

    public SelfIntroSkill() {
        super(InterviewPhase.SELF_INTRO);
    }

    @Override
    public String generateOpeningQuestion(InterviewContext context) {
        context.setSelfIntroQuestionCount(0);
        return interviewerAgent.generateSelfIntroQuestion(context);
    }

    @Override
    public boolean needEvaluate(InterviewContext context, InterviewMessage answer) {
        // 自我介绍只有 1 题，不评估回答质量
        return false;
    }

    @Override
    public EvaluationSignal extractSignal(EvaluationResult result) {
        // 本环节不评估，方法不会被调用
        EvaluationSignal signal = new EvaluationSignal();
        signal.setSuggestedNextDepth(1);
        signal.setContinueProbing(true);
        return signal;
    }

    @Override
    public void updateCounters(InterviewContext context, EvaluationSignal signal) {
        // 自我介绍不累计连续优秀/失败，避免影响后续专业面试
    }

    @Override
    public NextAction decideNextAction(InterviewContext context, EvaluationSignal signal) {
        // 自我介绍只有 1 题，回答后直接切换至下一环节
        return nextPhaseOrEnd(context);
    }

    @Override
    public String generateNextQuestion(InterviewContext context, String previousQuestion,
                                       String previousAnswer, EvaluationSignal signal) {
        // 自我介绍只有 1 题，该方法不应被调用
        throw new IllegalStateException("自我介绍环节只有 1 题，不应生成下一题");
    }
}
