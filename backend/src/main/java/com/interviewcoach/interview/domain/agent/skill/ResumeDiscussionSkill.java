package com.interviewcoach.interview.domain.agent.skill;

import com.interviewcoach.interview.domain.entity.InterviewMessage;
import com.interviewcoach.interview.domain.entity.InterviewPhase;
import com.interviewcoach.interview.domain.model.EvaluationSignal;
import com.interviewcoach.interview.domain.model.InterviewContext;
import com.interviewcoach.interview.domain.model.NextAction;
import com.interviewcoach.resume.domain.model.UserProfileData;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 简历探讨环节的项目轮转策略。
 *
 * <p>项目列表来自创建时正式简历事实快照；进入环节从索引 0 开始，每道回答都执行安全评估，
 * 每项目达到当前固定预算 3 后切换项目，全部项目耗尽后进入下一环节。每项目 3 题的精确
 * 产品依据缺失；没有项目时仍会生成固定模板或出题降级问题。</p>
 */
@Component
public class ResumeDiscussionSkill extends AbstractInterviewSkill {

    /** 将本实现注册为简历探讨环节 Skill。 */
    public ResumeDiscussionSkill() {
        super(InterviewPhase.RESUME_DISCUSSION);
    }

    @Override
    /** 将项目索引和本环节题目计数归零，再生成第一个项目核验问题。 */
    public String generateOpeningQuestion(InterviewContext context) {
        context.setCurrentProjectIndex(0);
        context.setCurrentPhaseQuestionCount(0);
        return interviewerAgent.generateResumeQuestion(context);
    }

    @Override
    /** 每道简历探讨回答都尝试安全评估；失败时 Coordinator 使用中性信号。 */
    public boolean needEvaluate(InterviewContext context, InterviewMessage answer) {
        // 评估质量信号不直接决定项目切换；当前切换仍由每项目题目预算控制。
        return true;
    }

    @Override
    /**
     * 根据每项目题目计数和项目数量决定继续、切项目或切环节；评估信号不改变当前数量规则。
     */
    public NextAction decideNextAction(InterviewContext context, EvaluationSignal signal) {
        List<?> projects = getProjects(context);
        int projectCount = projects == null ? 0 : projects.size();
        int maxPerProject = context.getMaxResumeQuestionsPerProject() != null
                ? context.getMaxResumeQuestionsPerProject() : 3;

        if (context.getCurrentPhaseQuestionCount() + 1 >= maxPerProject) {
            if (context.getCurrentProjectIndex() + 1 >= Math.max(1, projectCount)) {
                return nextPhaseOrEnd(context);
            }
            return hasNextProject(context) ? NextAction.SWITCH_TOPIC : NextAction.NEXT_PHASE;
        }
        return NextAction.FOLLOW_UP;
    }

    @Override
    /** 当前项目题目计数加一后，再围绕同一项目生成下一题。 */
    public String generateNextQuestion(InterviewContext context, String previousQuestion,
                                       String previousAnswer, EvaluationSignal signal) {
        context.setCurrentPhaseQuestionCount(context.getCurrentPhaseQuestionCount() + 1);
        return interviewerAgent.generateResumeQuestion(context);
    }

    @Override
    /**
     * 为切换后的项目生成问题。
     *
     * <p>Coordinator 调用本方法前已经切换一次项目；当前实现再次调用
     * {@link #switchToNextTopic(InterviewContext)}，可能继续推进项目索引。该既有行为未在本 Task 修改。</p>
     */
    public String generateTransitionQuestion(InterviewContext context) {
        switchToNextTopic(context);
        return interviewerAgent.generateResumeQuestion(context);
    }

    @Override
    /** 推进到下一个简历项目并重置本项目题目计数；没有下一项目时返回 {@code false}。 */
    public boolean switchToNextTopic(InterviewContext context) {
        List<?> projects = getProjects(context);
        int projectCount = projects == null ? 0 : projects.size();
        if (context.getCurrentProjectIndex() + 1 >= projectCount) {
            return false;
        }
        context.setCurrentProjectIndex(context.getCurrentProjectIndex() + 1);
        context.setCurrentPhaseQuestionCount(0);
        return true;
    }

    /** 判断正式事实快照的项目列表中是否还有下一个项目。 */
    private boolean hasNextProject(InterviewContext context) {
        List<?> projects = getProjects(context);
        return projects != null && context.getCurrentProjectIndex() + 1 < projects.size();
    }

    /** 从创建时正式事实画像快照读取项目经历列表；画像为空时返回 {@code null}。 */
    private List<?> getProjects(InterviewContext context) {
        if (context.getUserProfile() == null) {
            return null;
        }
        return context.getUserProfile().getProjectExperience();
    }
}
