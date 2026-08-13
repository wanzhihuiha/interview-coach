package com.interviewcoach.interview.domain.agent.skill;

import com.interviewcoach.interview.domain.entity.InterviewMessage;
import com.interviewcoach.interview.domain.entity.InterviewPhase;
import com.interviewcoach.interview.domain.model.EvaluationResult;
import com.interviewcoach.interview.domain.model.EvaluationSignal;
import com.interviewcoach.interview.domain.model.InterviewContext;
import com.interviewcoach.interview.domain.model.NextAction;

/**
 * 单个面试环节的服务端策略扩展契约。
 *
 * <p>Coordinator 按当前环节从注册表解析实现，并依次调用评估判断、信号派生、计数更新、
 * 动作决策和出题方法。实现可以修改传入的 {@link InterviewContext}，应用层会在轮次完成时
 * 把这些状态写回面试实体；模型响应不能直接实现本接口或决定流程动作。</p>
 */
public interface InterviewSkill {

    /** @return 当前实现是否负责给定服务端环节。 */
    boolean supports(InterviewPhase phase);

    /**
     * 初始化该环节需要的上下文状态并生成首题或结束语。
     *
     * @return 可持久化并发送给候选人的非空文本
     */
    String generateOpeningQuestion(InterviewContext context);

    /**
     * 根据当前上下文和尚未落库的候选人消息判断本轮是否调用评估模型。
     * 返回 {@code false} 时 Coordinator 使用中性信号继续确定性数量规则。
     */
    boolean needEvaluate(InterviewContext context, InterviewMessage answer);

    /**
     * 把经过安全校验的评分转换为只含流程所需信息的信号；实现不得接受模型提供的动作字段。
     */
    EvaluationSignal extractSignal(EvaluationResult result);

    /**
     * 根据服务端信号修改连续质量等环节计数；评估失败时 Coordinator 不调用该方法。
     */
    void updateCounters(InterviewContext context, EvaluationSignal signal);

    /**
     * 只根据服务端上下文和派生信号选择追问、换主题、换环节或结束。
     */
    NextAction decideNextAction(InterviewContext context, EvaluationSignal signal);

    /**
     * 生成当前环节内的下一题。
     *
     * @param context 当前可修改的运行上下文
     * @param previousQuestion 上一轮问题
     * @param previousAnswer 上一轮回答
     * @param signal 评估信号，可能是不含评分的中性信号
     * @return 当前环节内的下一题；不支持继续时实现可显式失败
     */
    String generateNextQuestion(InterviewContext context, String previousQuestion,
                                String previousAnswer, EvaluationSignal signal);

    /**
     * 当前环节已经完成主题/项目/场景切换后生成过渡问题。
     * 默认返回 {@code null}；Coordinator 只有在 {@link #switchToNextTopic(InterviewContext)}
     * 返回成功时才调用它。
     */
    default String generateTransitionQuestion(InterviewContext context) {
        return null;
    }

    /**
     * 修改上下文以切换当前环节内的下一个主题、项目或场景。
     *
     * @return {@code true} 表示切换成功；{@code false} 表示没有下一项，应由 Coordinator 切换环节
     */
    default boolean switchToNextTopic(InterviewContext context) {
        return false;
    }
}
