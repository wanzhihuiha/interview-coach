package com.interviewcoach.interview.domain.agent.skill;

import com.interviewcoach.interview.domain.entity.InterviewMessage;
import com.interviewcoach.interview.domain.entity.InterviewPhase;
import com.interviewcoach.interview.domain.model.EvaluationSignal;
import com.interviewcoach.interview.domain.model.InterviewContext;
import com.interviewcoach.interview.domain.model.NextAction;
import com.interviewcoach.position.domain.model.PositionProfileData;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 专业面试环节的多主题递进策略。
 *
 * <p>主题来自岗位画像的探查方向；缺失时使用“综合能力”。每轮根据服务端派生的质量事件在
 * L1～L5 间升降一级，在追问预算耗尽、L5、连续困难或总题数预算触发时换主题/环节。
 * L1～L5、单主题追问 5 等默认预算的精确产品依据缺失。</p>
 */
@Component
public class ProfessionalSkill extends AbstractInterviewSkill {

    /** 将本实现注册为专业面试环节 Skill。 */
    public ProfessionalSkill() {
        super(InterviewPhase.PROFESSIONAL);
    }

    @Override
    /** 初始化首个岗位探查主题，并从固定 L1 深度请求该主题首题。 */
    public String generateOpeningQuestion(InterviewContext context) {
        initTopics(context);
        return interviewerAgent.generateProfessionalQuestion(context, null, null, 1);
    }

    /**
     * 当前回答序号距离上次评估序号达到 2，或到达主题追问上限、连续优秀/困难阈值时触发评估。
     * 正常消息序号每轮增加 2，因此当前条件通常意味着每道专业题都尝试评估。
     */
    @Override
    public boolean needEvaluate(InterviewContext context, InterviewMessage answer) {
        int seq = answer.getSeqNo() == null ? 0 : answer.getSeqNo();
        int last = context.getLastEvaluationSeq() == null ? 0 : context.getLastEvaluationSeq();
        int distance = seq - last;
        return distance >= 2
                || context.getCurrentTopicFollowUpCount() >= context.getMaxFollowUpPerTopic()
                || context.getConsecutiveFailures() >= 2
                || context.getConsecutiveExcellence() >= 2;
    }

    /**
     * 按服务端数量、深度和连续困难规则决定追问、换主题或换环节；不会读取模型提供的流程字段。
     */
    @Override
    public NextAction decideNextAction(InterviewContext context, EvaluationSignal signal) {
        // 兼容服务端信号中的换主题标记；安全模型响应本身不能提供该字段。
        if (Boolean.TRUE.equals(signal.getShouldSwitchTopic())) {
            return hasNextTopic(context) ? NextAction.SWITCH_TOPIC : NextAction.NEXT_PHASE;
        }
        // L1 已连续两次困难时不再降低深度，改为切换下一主题或环节。
        if (context.getConsecutiveFailures() >= 2 && context.getCurrentDepth() <= 1) {
            return hasNextTopic(context) ? NextAction.SWITCH_TOPIC : NextAction.NEXT_PHASE;
        }
        // 当前主题达到服务端追问预算后不再出同主题问题。
        if (context.getCurrentTopicFollowUpCount() >= context.getMaxFollowUpPerTopic()) {
            return hasNextTopic(context) ? NextAction.SWITCH_TOPIC : NextAction.NEXT_PHASE;
        }
        // 当前深度已达固定 L5 上限时切换主题或环节。
        if (context.getCurrentDepth() >= 5) {
            return hasNextTopic(context) ? NextAction.SWITCH_TOPIC : NextAction.NEXT_PHASE;
        }
        // 会话消息计数达到当前总预算时直接结束当前环节；该计数包含双方消息而非纯问题数。
        if (context.getTotalQuestionCount() >= context.getMaxQuestions()) {
            return nextPhaseOrEnd(context);
        }
        return NextAction.FOLLOW_UP;
    }

    /**
     * 生成同一主题的下一题。优秀事件升一级，困难事件降一级，中性或评估失败保持当前深度。
     */
    @Override
    public String generateNextQuestion(InterviewContext context, String previousQuestion,
                                       String previousAnswer, EvaluationSignal signal) {
        int currentDepth = context.getCurrentDepth() == null ? 1 : context.getCurrentDepth();
        // 深度调整只使用服务端从已校验分数得到的固定事件，不接收模型建议的目标深度。
        int targetDepth = currentDepth;
        if ("EXCELLENT".equalsIgnoreCase(signal.getKeyEventType())) {
            targetDepth = Math.min(5, currentDepth + 1);
        } else if ("STRUGGLED".equalsIgnoreCase(signal.getKeyEventType())) {
            targetDepth = Math.max(1, currentDepth - 1);
        }
        context.setCurrentDepth(targetDepth);
        context.setCurrentTopicFollowUpCount(context.getCurrentTopicFollowUpCount() + 1);
        // 面试官把上一问答作为 DATA_ONLY 数据，并按服务端 targetDepth 生成追问题。
        return interviewerAgent.generateProfessionalQuestion(context, previousQuestion, previousAnswer, targetDepth);
    }

    /**
     * 为当前上下文已切换到的主题生成过渡题。
     *
     * <p>Coordinator 在调用本方法前已经执行过一次 {@link #switchToNextTopic(InterviewContext)}；
     * 当前实现会再次调用该方法，因而会继续推进一项。该既有行为未在本注释 Task 中修改。</p>
     */
    @Override
    public String generateTransitionQuestion(InterviewContext context) {
        switchToNextTopic(context);
        return interviewerAgent.generateTopicTransition(context,
                context.getCurrentTopicName(), context.getCurrentTopicId());
    }

    /**
     * 切换到岗位分析给出的下一个探查主题，并重置新主题的深度、追问数和连续质量计数。
     */
    @Override
    public boolean switchToNextTopic(InterviewContext context) {
        List<PositionProfileData.ProbingDirection> directions = getDirections(context);
        if (directions == null || directions.isEmpty()) {
            return false;
        }
        int currentIndex = -1;
        for (int i = 0; i < directions.size(); i++) {
            if (directions.get(i).getDirection().equals(context.getCurrentTopicName())) {
                currentIndex = i;
                break;
            }
        }
        int nextIndex = currentIndex + 1;
        if (nextIndex >= directions.size()) {
            return false;
        }
        PositionProfileData.ProbingDirection next = directions.get(nextIndex);
        context.setCurrentTopicId("topic-" + nextIndex + "-" + next.getDirection());
        context.setCurrentTopicName(next.getDirection());
        context.setCurrentDepth(1);
        context.setCurrentTopicFollowUpCount(0);
        context.setConsecutiveFailures(0);
        context.setConsecutiveExcellence(0);
        return true;
    }

    /**
     * 使用岗位探查方向初始化首个主题；没有方向时使用不带岗位推断的“综合能力”主题。
     */
    private void initTopics(InterviewContext context) {
        List<PositionProfileData.ProbingDirection> directions = getDirections(context);
        if (directions == null || directions.isEmpty()) {
            context.setCurrentTopicId("general");
            context.setCurrentTopicName("综合能力");
            return;
        }
        PositionProfileData.ProbingDirection first = directions.get(0);
        context.setCurrentTopicId("topic-0-" + first.getDirection());
        context.setCurrentTopicName(first.getDirection());
        context.setCurrentDepth(1);
        context.setCurrentTopicFollowUpCount(0);
    }

    /** 按当前主题名称在岗位探查方向中定位索引，并判断是否还有下一项。 */
    private boolean hasNextTopic(InterviewContext context) {
        List<PositionProfileData.ProbingDirection> directions = getDirections(context);
        if (directions == null || directions.isEmpty()) {
            return false;
        }
        int currentIndex = -1;
        for (int i = 0; i < directions.size(); i++) {
            if (directions.get(i).getDirection().equals(context.getCurrentTopicName())) {
                currentIndex = i;
                break;
            }
        }
        return currentIndex + 1 < directions.size();
    }

    /** 从创建时岗位画像快照读取探查方向；画像为空时返回 {@code null}。 */
    private List<PositionProfileData.ProbingDirection> getDirections(InterviewContext context) {
        if (context.getPositionProfile() == null) {
            return null;
        }
        return context.getPositionProfile().getProbingDirections();
    }
}
