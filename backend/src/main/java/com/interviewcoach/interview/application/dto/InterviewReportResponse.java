package com.interviewcoach.interview.application.dto;

import java.util.List;
import java.util.Map;
import lombok.Data;

/**
 * 面试评估报告响应。
 *
 * <p>首次读取时由应用服务根据消息和岗位快照即时生成、做有限字段替换后保存；后续读取优先
 * 反序列化已保存报告。该结构同时供报告接口展示和成长方案服务提取分数、等级及薄弱点。</p>
 */
@Data
public class InterviewReportResponse {

    /** 报告对应的面试 ID。 */
    private Long interviewId;

    /** 应用服务按当前回答字符数规则计算的综合分。 */
    private Integer overallScore;

    /** 由综合分阈值映射出的中文等级。 */
    private String grade;

    /** 以环节编码为键的完成情况和面试官问题数汇总。 */
    private Map<String, PhaseSummary> phases;

    /** 由当前综合分及固定偏移组装的五维分数。 */
    private DimensionScores dimensions;

    /** 报告 Agent 根据完整问答生成的优势；模型失败时使用本地降级结果。 */
    private List<String> strengths;

    /** 报告 Agent 根据完整问答和岗位画像生成的薄弱点；成长方案会消费该列表。 */
    private List<String> weaknesses;

    /** 报告关键事件列表；当前生成流程固定写入空列表。 */
    private List<String> keyEvents;

    /** 由等级和薄弱点拼接的报告结论。 */
    private String conclusion;

    /** 根据本响应各字段构建的 Markdown 正文；读取缓存实体时会重新构建。 */
    private String mdContent;

    /** 单个环节在报告中的展示汇总。 */
    @Data
    public static class PhaseSummary {
        /** 环节编码对应的中文展示名称。 */
        private String phaseLabel;

        /** 当前环节是否按面试进度判定为已完成。 */
        private Boolean completed;

        /** 该环节持久化的面试官消息数量。 */
        private Integer questionCount;
    }

    /** 报告页面和成长方案使用的五维分数集合。 */
    @Data
    public static class DimensionScores {
        /** 当前实现直接取综合分的技术深度分。 */
        private Integer technicalDepth;

        /** 当前实现以综合分减去固定偏移得到的技术广度分。 */
        private Integer technicalBreadth;

        /** 当前实现以综合分减去固定偏移得到的实践经验分。 */
        private Integer practicalExperience;

        /** 当前实现以综合分减去固定偏移得到的表达能力分。 */
        private Integer expression;

        /** 当前实现以综合分减去固定偏移得到的学习能力分。 */
        private Integer learningAbility;
    }
}
