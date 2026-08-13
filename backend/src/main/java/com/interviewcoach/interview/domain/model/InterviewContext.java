package com.interviewcoach.interview.domain.model;

import com.interviewcoach.interview.domain.entity.InterviewPhase;
import com.interviewcoach.position.domain.model.PositionProfileData;
import com.interviewcoach.resume.domain.model.UserProfileData;
import com.interviewcoach.resume.domain.model.ResumeProfileAnalysisData;
import java.util.ArrayList;
import java.util.List;
import lombok.Data;

/**
 * 一场面试在 Coordinator 与各环节 Skill 之间传递的可变运行上下文。
 *
 * <p>创建和回答服务从会话快照建立本对象，Skill 在事务外推进主题、深度、计数和环节；轮次
 * 状态服务再把这些字段写回 {@code Interview}。正式简历画像和岗位画像来自创建时快照，辅助
 * 分析只作为选题线索，不参与评估。</p>
 */
@Data
public class InterviewContext {

    /** 当前面试 ID，用于把运行结果写回同一会话。 */
    private Long interviewId;
    /** 当前可信用户 ID；由创建快照提供，不从回答文本推断。 */
    private Long userId;
    /** 创建时固化的正式简历事实画像。 */
    private UserProfileData userProfile;
    /** 创建时可用的辅助分析线索，缺失时为空且不阻断面试。 */
    private ResumeProfileAnalysisData userProfileAnalysis;
    /** 创建时固化的正式岗位画像。 */
    private PositionProfileData positionProfile;

    /**
     * 岗位大类（如 TECH、PRODUCT、OPERATION），用于 RAG 分层和成长方案差异化。
     */
    private String jobCategory;

    /** 用户选择经服务端去重排序并追加 ENDING 后的环节列表。 */
    private List<InterviewPhase> selectedPhases = new ArrayList<>();
    /** 当前正在处理的服务端环节。 */
    private InterviewPhase currentPhase;

    /** 当前专业主题标识，非专业环节通常为空。 */
    private String currentTopicId;
    /** 当前专业主题、简历项目或行为场景名称。 */
    private String currentTopicName;
    /** 当前专业题深度，通常限制在 L1～L5。 */
    private Integer currentDepth = 1;
    /** 当前专业主题已经生成的追问次数。 */
    private Integer currentTopicFollowUpCount = 0;
    /** 连续困难评估事件计数，供专业 Skill 换主题判断。 */
    private Integer consecutiveFailures = 0;
    /** 连续优秀评估事件计数，供专业 Skill 记录质量趋势。 */
    private Integer consecutiveExcellence = 0;

    /** 当前简历项目的零基索引。 */
    private Integer currentProjectIndex = 0;

    /** 当前行为场景的零基索引；当前实现每道继续题递增一次。 */
    private Integer currentBehavioralIndex = 0;

    /** 自我介绍已生成题目计数；当前单题 Skill 只在进入时归零。 */
    private Integer selfIntroQuestionCount = 0;

    /** 当前已持久化消息计数，首题为 1，回答和下一题各占一个序号。 */
    private Integer totalQuestionCount = 0;
    /** 当前环节题目进度计数，由具体 Skill 解释。 */
    private Integer currentPhaseQuestionCount = 0;
    /** 最近一次尝试评估的候选人消息序号，避免重复评估。 */
    private Integer lastEvaluationSeq = 0;
    /** 当前会话总消息预算，固定默认 30，精确依据缺失。 */
    private Integer maxQuestions = 30;
    /** 当前主题追问预算，固定默认 5，精确依据缺失。 */
    private Integer maxFollowUpPerTopic = 5;

    /** 自我介绍预算字段当前无消费者，不能据此推断实际题数。 */
    private Integer maxSelfIntroQuestions = 3;
    /** 行为面试默认题目预算，当前代码回退为 4，精确依据缺失。 */
    private Integer maxBehavioralQuestions = 4;
    /** 每个简历项目默认题目预算，当前代码回退为 3，精确依据缺失。 */
    private Integer maxResumeQuestionsPerProject = 3;

    /** 当前环节是否已是选择列表最后一项；空列表或未知环节返回 false。 */
    public boolean isLastPhase() {
        return selectedPhases != null && !selectedPhases.isEmpty()
                && currentPhase == selectedPhases.get(selectedPhases.size() - 1);
    }

    /** 返回选择列表中当前环节的下一项；空列表、未知环节或已经末项返回 {@code null}。 */
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
