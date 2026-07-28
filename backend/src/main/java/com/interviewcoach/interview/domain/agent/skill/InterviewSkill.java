package com.interviewcoach.interview.domain.agent.skill;

import com.interviewcoach.interview.domain.entity.InterviewMessage;
import com.interviewcoach.interview.domain.entity.InterviewPhase;
import com.interviewcoach.interview.domain.model.EvaluationResult;
import com.interviewcoach.interview.domain.model.EvaluationSignal;
import com.interviewcoach.interview.domain.model.InterviewContext;
import com.interviewcoach.interview.domain.model.NextAction;

/**
 * 面试环节 Skill：封装某一环节内的提问、评估、切换策略。
 * 协调者 Agent 根据当前环节（意图）加载对应 Skill，完成该环节的流程编排。
 */
public interface InterviewSkill {

    /**
     * 是否支持该环节。
     */
    boolean supports(InterviewPhase phase);

    /**
     * 生成进入该环节后的首题。
     */
    String generateOpeningQuestion(InterviewContext context);

    /**
     * 判断当前回答是否需要 LLM 评估。
     */
    boolean needEvaluate(InterviewContext context, InterviewMessage answer);

    /**
     * 从评估结果中提取精简信号。
     */
    EvaluationSignal extractSignal(EvaluationResult result);

    /**
     * 根据评估信号更新环节内部计数器。
     */
    void updateCounters(InterviewContext context, EvaluationSignal signal);

    /**
     * 决定下一步动作。
     */
    NextAction decideNextAction(InterviewContext context, EvaluationSignal signal);

    /**
     * 生成当前环节内的下一题。
     *
     * @param previousQuestion 上一轮问题
     * @param previousAnswer   上一轮回答
     * @param signal           评估信号（可能为中性信号）
     */
    String generateNextQuestion(InterviewContext context, String previousQuestion,
                                String previousAnswer, EvaluationSignal signal);

    /**
     * 当前环节内切换主题/项目/场景后生成过渡问题。
     * 不支持切换的 Skill 可返回 null，协调者将转而切换环节。
     */
    default String generateTransitionQuestion(InterviewContext context) {
        return null;
    }

    /**
     * 切换当前环节内的下一个主题/项目/场景。
     * 返回 true 表示切换成功，false 表示已无下一个，应切换环节。
     */
    default boolean switchToNextTopic(InterviewContext context) {
        return false;
    }
}
