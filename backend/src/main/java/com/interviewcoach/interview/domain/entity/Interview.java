package com.interviewcoach.interview.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 一场模拟面试的持久化会话和可恢复运行状态。
 *
 * <p>创建状态服务从用户选择的简历、岗位和正式画像生成本实体并固化快照；轮次状态服务持续
 * 写回 Skill 主题、深度、计数及 reservation；查询接口和报告服务读取它恢复会话和展示结果。</p>
 */
@Entity
@Table(name = "interview")
@Getter
@Setter
public class Interview {

    /** 数据库自增面试 ID，也是消息、报告、资源锁和 Redis 槽位关联本场会话的标识。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 本场面试所属的求职者用户 ID，所有用户接口查询均须同时限定该值。 */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 创建本场时选中的本人简历 ID；进行中时对应简历通常保存本面试锁 ID。 */
    @Column(name = "resume_id", nullable = false)
    private Long resumeId;

    /** 创建本场时选中的私有或公共岗位 ID。 */
    @Column(name = "position_id", nullable = false)
    private Long positionId;

    /** 创建时固化的岗位名称，供详情和报告展示，不随原岗位后续修改。 */
    @Column(name = "position_name_snapshot", nullable = false, length = 255)
    private String positionNameSnapshot;

    /** 创建时固化的公司名称，可为空；报告有限字段替换会把该文本作为精确匹配目标。 */
    @Column(name = "company_name_snapshot", length = 255)
    private String companyNameSnapshot;

    /** 创建时固化的岗位类别编码，供题库分层和运行上下文使用。 */
    @Column(name = "job_category_snapshot", nullable = false, length = 30)
    private String jobCategorySnapshot;

    /** 创建时正式简历事实画像的 JSON 快照，是每轮面试的必需输入。 */
    @Column(name = "user_profile", columnDefinition = "TEXT")
    private String userProfileSnapshot;

    /** 创建时可用的辅助分析 JSON 快照；只供选题核验线索使用，不参与回答评估和报告评分。 */
    @Column(name = "user_profile_analysis", columnDefinition = "TEXT")
    private String userProfileAnalysisSnapshot;

    /** 创建时正式岗位画像的 JSON 快照，是专业主题和报告分析的必需输入。 */
    @Column(name = "position_profile", columnDefinition = "TEXT")
    private String positionProfileSnapshot;

    /** 用户选择经去重排序并追加结束环节后的英文枚举名 JSON 列表。 */
    @Column(name = "selected_phases", nullable = false, length = 500)
    private String selectedPhases;

    /** 当前服务端环节；首题初始化、每轮写回和结束流程会推进该状态。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "current_phase", nullable = false, length = 30)
    private InterviewPhase currentPhase;

    /** 当前专业主题的服务端标识；其他环节或尚未初始化主题时可为空。 */
    @Column(name = "current_topic_id", length = 100)
    private String currentTopicId;

    /** 当前专业主题、简历项目或场景的展示名称，未建立时可为空。 */
    @Column(name = "current_topic_name", length = 100)
    private String currentTopicName;

    /** 当前专业问题深度，默认 L1；专业 Skill 在合法评估信号下限制于 L1～L5。 */
    @Column(name = "current_depth")
    private Integer currentDepth = 1;

    /** 当前专业主题已经生成的追问题计数，切换主题时归零。 */
    @Column(name = "current_topic_follow_up_count")
    private Integer currentTopicFollowUpCount = 0;

    /** 合法评估连续派生 STRUGGLED 事件的次数；中性/优秀事件会按 Skill 规则重置。 */
    @Column(name = "consecutive_failures")
    private Integer consecutiveFailures = 0;

    /** 合法评估连续派生 EXCELLENT 事件的次数；中性/困难事件会按 Skill 规则重置。 */
    @Column(name = "consecutive_excellence")
    private Integer consecutiveExcellence = 0;

    /** 最近一次已尝试评估的候选人消息序号；评估失败也写入，避免重复调用同一回答。 */
    @Column(name = "last_evaluation_seq")
    private Integer lastEvaluationSeq = 0;

    /**
     * 断线恢复问题或内部轮次 reservation；详情映射会隐藏带内部前缀的 reservation token。
     */
    @Column(name = "pending_question", columnDefinition = "TEXT")
    private String pendingQuestion;

    /** 会话生命周期状态，创建默认为进行中，正常推进或主动/启动清理进入终态。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private InterviewStatus status = InterviewStatus.IN_PROGRESS;

    /**
     * 当前实际作为消息序号基准的总消息计数：首题为 1，每轮回答和下一题各增加 1；名称不等同纯问题数。
     */
    @Column(name = "total_question_count")
    private Integer totalQuestionCount = 0;

    /** 当前环节内部题目进度计数，由 Skill 推进并在环节切换写回时归零。 */
    @Column(name = "current_phase_question_count")
    private Integer currentPhaseQuestionCount = 0;

    /** 自我介绍题计数；当前固定单题 Skill 只在进入环节时归零，不读取预算继续出题。 */
    @Column(name = "self_intro_question_count")
    private Integer selfIntroQuestionCount = 0;

    /** 简历探讨当前项目的零基索引。 */
    @Column(name = "current_project_index")
    private Integer currentProjectIndex = 0;

    /** 行为面试当前固定场景的零基索引。 */
    @Column(name = "current_behavioral_index")
    private Integer currentBehavioralIndex = 0;

    /** 面试开始时间；首次持久化前未显式设置时由生命周期回调写入当前本地时间。 */
    @Column(name = "started_at")
    private LocalDateTime startedAt;

    /** 正常结束、中断或启动清理写入的本地结束时间；进行中为空。 */
    @Column(name = "ended_at")
    private LocalDateTime endedAt;

    /** 首次持久化时写入且后续不可更新的本地创建时间。 */
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** 首次持久化及每次 JPA 更新前刷新的本地更新时间。 */
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /** 首次持久化前统一设置创建/更新时间，并在缺失时把开始时间设为同一业务时间。 */
    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
        if (this.startedAt == null) {
            this.startedAt = now;
        }
    }

    /** JPA 更新前刷新更新时间；不改动开始或结束时间。 */
    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
