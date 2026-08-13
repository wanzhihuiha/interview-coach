package com.interviewcoach.interview.domain.agent.skill;

import com.interviewcoach.interview.domain.entity.InterviewMessage;
import com.interviewcoach.interview.domain.entity.InterviewPhase;
import com.interviewcoach.interview.domain.model.EvaluationResult;
import com.interviewcoach.interview.domain.model.EvaluationSignal;
import com.interviewcoach.interview.domain.model.InterviewContext;
import com.interviewcoach.interview.domain.model.NextAction;
import org.springframework.stereotype.Component;

/**
 * 行为面试环节的固定场景策略。
 *
 * <p>首题使用场景索引 0；每个回答都触发评估，然后 {@link #generateNextQuestion} 立即把索引
 * 加一，因此当前实现是每道下一题切换一个场景，并非每场景 1～2 题。达到上下文预算后进入
 * 下一环节。五个固定场景及默认预算的精确产品依据缺失。</p>
 */
@Component
public class BehavioralSkill extends AbstractInterviewSkill {

    /** 用于默认题目预算和场景数量判断的固定场景集合。 */
    private static final String[] SCENARIOS = {
            "团队协作", "问题解决", "成长学习", "领导力", "沟通表达"
    };

    /** 将本实现注册为行为面试环节 Skill。 */
    public BehavioralSkill() {
        super(InterviewPhase.BEHAVIORAL);
    }

    @Override
    /** 将行为场景索引重置为 0，并由面试官生成第一道 STAR 风格问题。 */
    public String generateOpeningQuestion(InterviewContext context) {
        context.setCurrentBehavioralIndex(0);
        return interviewerAgent.generateBehavioralQuestion(context);
    }

    @Override
    /**
     * 回答消息序号每轮增加 2，因此距离上次评估序号达到 2 时对每道行为题执行评估。
     */
    public boolean needEvaluate(InterviewContext context, InterviewMessage answer) {
        int seq = answer.getSeqNo() == null ? 0 : answer.getSeqNo();
        int last = context.getLastEvaluationSeq() == null ? 0 : context.getLastEvaluationSeq();
        return seq - last >= 2;
    }

    @Override
    /**
     * 派生优秀/困难事件但始终允许数量规则继续；空评估返回不含质量事件的中性信号。
     */
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
    /**
     * 达到配置的行为题预算后进入下一环节，否则继续出题；质量事件不改变题目数量。
     */
    public NextAction decideNextAction(InterviewContext context, EvaluationSignal signal) {
        int maxBehavioral = context.getMaxBehavioralQuestions() != null
                ? context.getMaxBehavioralQuestions() : SCENARIOS.length;
        if (context.getCurrentBehavioralIndex() + 1 >= maxBehavioral) {
            return nextPhaseOrEnd(context);
        }
        return NextAction.FOLLOW_UP;
    }

    @Override
    /** 索引加一后生成下一场景题，因此每次继续都会立即换场景。 */
    public String generateNextQuestion(InterviewContext context, String previousQuestion,
                                       String previousAnswer, EvaluationSignal signal) {
        context.setCurrentBehavioralIndex(context.getCurrentBehavioralIndex() + 1);
        return interviewerAgent.generateBehavioralQuestion(context);
    }

    /** 行为面试的“主题”即场景；本方法也会把索引加一后生成下一场景题。 */
    @Override
    public String generateTransitionQuestion(InterviewContext context) {
        context.setCurrentBehavioralIndex(context.getCurrentBehavioralIndex() + 1);
        return interviewerAgent.generateBehavioralQuestion(context);
    }

    @Override
    /** 在固定五场景范围内推进索引；最后一个场景之后返回无下一项。 */
    public boolean switchToNextTopic(InterviewContext context) {
        if (context.getCurrentBehavioralIndex() + 1 >= SCENARIOS.length) {
            return false;
        }
        context.setCurrentBehavioralIndex(context.getCurrentBehavioralIndex() + 1);
        return true;
    }
}
