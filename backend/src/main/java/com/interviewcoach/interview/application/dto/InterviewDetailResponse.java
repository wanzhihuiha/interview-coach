package com.interviewcoach.interview.application.dto;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import lombok.Data;

/**
 * 面试会话详情响应。
 *
 * <p>简历、岗位和画像内容在创建时已固化到面试记录；本响应只返回会话状态和岗位展示快照，
 * 不暴露画像 JSON 或回答处理中使用的 reservation token。进行中的面试尚不计算综合分和等级。</p>
 */
@Data
public class InterviewDetailResponse {

    /** 面试数据库 ID。 */
    private Long interviewId;

    /** 创建本场面试时锁定并保存引用的简历 ID。 */
    private Long resumeId;

    /** 创建本场面试时保存引用的岗位 ID。 */
    private Long positionId;

    /** 当前会话状态的稳定枚举编码。 */
    private String status;

    /** 与 {@link #status} 对应的中文展示名称。 */
    private String statusLabel;

    /** 当前面试环节的稳定枚举编码。 */
    private String currentPhase;

    /** 与 {@link #currentPhase} 对应的中文展示名称。 */
    private String currentPhaseLabel;

    /** 当前专业主题或项目/场景名称；相应环节尚未建立主题时可为空。 */
    private String currentTopic;

    /** 当前题目的服务端深度；主要由专业面试 Skill 维护。 */
    private Integer currentDepth;

    /**
     * 当前已持久化的面试消息总数，而不是仅面试官问题数：首题记为 1，每轮回答和下一题各增加 1。
     */
    private Integer totalQuestionCount;

    /** 创建时固化的实际环节编码列表。 */
    private List<String> selectedPhases;

    /** 以已选环节编码为键、中文展示名称为值的映射。 */
    private Map<String, String> phaseLabels;

    /** 可供断线恢复的待处理问题文本；回答处理中使用的内部 reservation 标记会被转换为空值。 */
    private String pendingQuestion;

    /** 创建面试时固化的岗位名称，不随原岗位后续修改。 */
    private String positionTitle;

    /** 创建面试时固化的公司名称，可为空。 */
    private String companyName;

    /** 创建面试时固化的岗位类别编码。 */
    private String jobCategory;

    /** 已结束或已中断面试按候选人回答字符数规则计算的综合分；进行中为空。 */
    private Integer overallScore;

    /** 与 {@link #overallScore} 对应的当前中文等级；进行中为空。 */
    private String grade;

    /** 面试记录首次持久化时写入的本地日期时间。 */
    private LocalDateTime startedAt;

    /** 自然结束、主动中断或启动清理时写入的本地日期时间；进行中为空。 */
    private LocalDateTime endedAt;
}
