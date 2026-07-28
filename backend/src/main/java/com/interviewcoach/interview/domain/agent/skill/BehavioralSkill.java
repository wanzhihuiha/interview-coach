package com.interviewcoach.interview.domain.agent.skill;

import com.interviewcoach.interview.domain.entity.InterviewMessage;
import com.interviewcoach.interview.domain.entity.InterviewPhase;
import com.interviewcoach.interview.domain.model.EvaluationResult;
import com.interviewcoach.interview.domain.model.EvaluationSignal;
import com.interviewcoach.interview.domain.model.InterviewContext;
import com.interviewcoach.interview.domain.model.NextAction;
import org.springframework.stereotype.Component;

/**
 * 行为面试 Skill：由协调器在行为面试环节调用，并委托面试官 Agent 按场景顺序提问。
 * 未达到题数上限时，每次回答后都切换到下一场景，不在同一场景继续追问；
 * 达到上下文配置的行为题数量后，进入下一个已选择环节或结束环节（当前默认 4 题）。
 * 本环节返回的 {@code FOLLOW_UP} 实际表示继续本环节并换场景，不表示追问当前场景。
 */
@Component
public class BehavioralSkill extends AbstractInterviewSkill {

    private static final String[] SCENARIOS = {
            "团队协作", "问题解决", "成长学习", "领导力", "沟通表达"
    };

    public BehavioralSkill() {
        super(InterviewPhase.BEHAVIORAL);
    }

    /**
     * 进入行为面试时把场景索引重置为 0，再生成第一个场景问题。
     */
    @Override
    public String generateOpeningQuestion(InterviewContext context) {
        context.setCurrentBehavioralIndex(0);
        return interviewerAgent.generateBehavioralQuestion(context);
    }

    /**
     * 按消息序号判断是否评估本轮回答。
     * 正常一问一答会新增两条消息，因此序号与上次评估相差至少 2 时，通常表示每个回答都要评估。
     */
    @Override
    public boolean needEvaluate(InterviewContext context, InterviewMessage answer) {
        int seq = answer.getSeqNo() == null ? 0 : answer.getSeqNo();
        int last = context.getLastEvaluationSeq() == null ? 0 : context.getLastEvaluationSeq();
        return seq - last >= 2;
    }

    /**
     * 将五个评分维度的平均值转换为行为面试事件。
     * 平均分不低于 85 标记优秀，低于 55 标记受阻；无论结果如何都固定允许继续本环节。
     */
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

    /**
     * 根据场景索引和行为题上限决定继续或换环节。
     *
     * <p>该决策不读取评估信号。未到上限时返回 {@code FOLLOW_UP}，后续实际会推进到下一个场景；
     * 上限超过 5 时，面试官生成器会按 5 个内置场景循环出题。</p>
     */
    @Override
    public NextAction decideNextAction(InterviewContext context, EvaluationSignal signal) {
        int maxBehavioral = context.getMaxBehavioralQuestions() != null
                ? context.getMaxBehavioralQuestions() : SCENARIOS.length;
        if (context.getCurrentBehavioralIndex() + 1 >= maxBehavioral) {
            return nextPhaseOrEnd(context);
        }
        return NextAction.FOLLOW_UP;
    }

    /**
     * 将场景索引加 1 后生成下一场景问题。
     * 上一轮问题、回答和评估信号当前均不参与生成，因此这不是对原场景的追问。
     */
    @Override
    public String generateNextQuestion(InterviewContext context, String previousQuestion,
                                       String previousAnswer, EvaluationSignal signal) {
        context.setCurrentBehavioralIndex(context.getCurrentBehavioralIndex() + 1);
        return interviewerAgent.generateBehavioralQuestion(context);
    }

    /**
     * 推进一次场景索引并生成问题。
     *
     * <p>正常行为面试决策不会返回 {@code SWITCH_TOPIC}，主链使用 {@link #generateNextQuestion} 推进。
     * 如果经协调器通用的切换主题分支调用，协调器会先调用 {@link #switchToNextTopic}，本方法随后再次加 1，
     * 当前行为会连续跨过两个场景。</p>
     */
    @Override
    public String generateTransitionQuestion(InterviewContext context) {
        context.setCurrentBehavioralIndex(context.getCurrentBehavioralIndex() + 1);
        return interviewerAgent.generateBehavioralQuestion(context);
    }

    /**
     * 按固定的 5 个内置场景判断并推进索引，不读取上下文中的行为题上限。
     * 当前正常行为面试主链不调用该方法。
     */
    @Override
    public boolean switchToNextTopic(InterviewContext context) {
        if (context.getCurrentBehavioralIndex() + 1 >= SCENARIOS.length) {
            return false;
        }
        context.setCurrentBehavioralIndex(context.getCurrentBehavioralIndex() + 1);
        return true;
    }
}
