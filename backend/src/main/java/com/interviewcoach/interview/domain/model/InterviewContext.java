package com.interviewcoach.interview.domain.model;

import com.interviewcoach.interview.domain.entity.InterviewPhase;
import com.interviewcoach.position.domain.model.PositionProfileData;
import com.interviewcoach.resume.domain.model.UserProfileData;
import com.interviewcoach.resume.domain.model.ResumeProfileAnalysisData;
import java.util.ArrayList;
import java.util.List;
import lombok.Data;

/**
 * 面试运行时上下文。
 */
@Data
public class InterviewContext {

    private Long interviewId;
    private Long userId;
    private UserProfileData userProfile;
    private ResumeProfileAnalysisData userProfileAnalysis;
    private PositionProfileData positionProfile;

    /**
     * 岗位大类（如 TECH、PRODUCT、OPERATION），用于 RAG 分层和成长方案差异化。
     */
    private String jobCategory;

    private List<InterviewPhase> selectedPhases = new ArrayList<>();
    private InterviewPhase currentPhase;

    // 专业面试相关
    private String currentTopicId;
    private String currentTopicName;
    private Integer currentDepth = 1;
    private Integer currentTopicFollowUpCount = 0;
    private Integer consecutiveFailures = 0;
    private Integer consecutiveExcellence = 0;

    // 简历探讨
    private Integer currentProjectIndex = 0;

    // 行为面试
    private Integer currentBehavioralIndex = 0;

    // 自我介绍
    private Integer selfIntroQuestionCount = 0;

    // 预算与计数
    private Integer totalQuestionCount = 0;
    private Integer currentPhaseQuestionCount = 0;
    private Integer lastEvaluationSeq = 0;
    private Integer maxQuestions = 30;
    private Integer maxFollowUpPerTopic = 5;

    // 各环节问题预算（由 Skill 自行解释）
    private Integer maxSelfIntroQuestions = 3;
    private Integer maxBehavioralQuestions = 4;
    private Integer maxResumeQuestionsPerProject = 3;

    public boolean isLastPhase() {
        return selectedPhases != null && !selectedPhases.isEmpty()
                && currentPhase == selectedPhases.get(selectedPhases.size() - 1);
    }

    public InterviewPhase nextPhase() {
        if (selectedPhases == null || selectedPhases.isEmpty()) {
            return null;
        }
        int index = selectedPhases.indexOf(currentPhase);
        if (index < 0 || index >= selectedPhases.size() - 1) {
            return null;
        }
        return selectedPhases.get(index + 1);
    }
}
