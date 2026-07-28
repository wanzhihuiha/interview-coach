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
 * 简历探讨 Skill：以项目为主题，每个项目下多问几题，项目耗尽后进入下一环节。
 */
@Component
public class ResumeDiscussionSkill extends AbstractInterviewSkill {

    public ResumeDiscussionSkill() {
        super(InterviewPhase.RESUME_DISCUSSION);
    }

    @Override
    public String generateOpeningQuestion(InterviewContext context) {
        context.setCurrentProjectIndex(0);
        context.setCurrentPhaseQuestionCount(0);
        return interviewerAgent.generateResumeQuestion(context);
    }

    @Override
    public boolean needEvaluate(InterviewContext context, InterviewMessage answer) {
        // 简历探讨每道题都评估，用于决定是否切换项目
        return true;
    }

    @Override
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
    public String generateNextQuestion(InterviewContext context, String previousQuestion,
                                       String previousAnswer, EvaluationSignal signal) {
        context.setCurrentPhaseQuestionCount(context.getCurrentPhaseQuestionCount() + 1);
        return interviewerAgent.generateResumeQuestion(context);
    }

    @Override
    public String generateTransitionQuestion(InterviewContext context) {
        switchToNextTopic(context);
        return interviewerAgent.generateResumeQuestion(context);
    }

    @Override
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

    private boolean hasNextProject(InterviewContext context) {
        List<?> projects = getProjects(context);
        return projects != null && context.getCurrentProjectIndex() + 1 < projects.size();
    }

    private List<?> getProjects(InterviewContext context) {
        if (context.getUserProfile() == null) {
            return null;
        }
        return context.getUserProfile().getProjectExperience();
    }
}
