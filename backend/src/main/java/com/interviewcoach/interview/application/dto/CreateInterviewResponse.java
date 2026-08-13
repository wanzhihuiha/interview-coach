package com.interviewcoach.interview.application.dto;

import java.util.List;
import java.util.Map;
import lombok.Data;

/**
 * 面试首题已经生成并与上下文一起落库后的创建响应。
 *
 * <p>环节和状态同时返回稳定英文编码及中文 Label，供前端保存流程值并直接展示中文名称。</p>
 */
@Data
public class CreateInterviewResponse {

    /** 已完成首题初始化的面试数据库 ID。 */
    private Long interviewId;

    /** 当前面试状态的稳定枚举编码，例如 {@code IN_PROGRESS}。 */
    private String status;

    /** 与 {@link #status} 对应的中文展示名称。 */
    private String statusLabel;

    /** 去重、排序并追加结束环节后的实际环节编码列表。 */
    private List<String> selectedPhases;

    /** 首题所属的当前环节编码。 */
    private String currentPhase;

    /** 与 {@link #currentPhase} 对应的中文展示名称。 */
    private String currentPhaseLabel;

    /** 已作为第一条面试官消息持久化的首题文本。 */
    private String firstQuestion;

    /** 前端按顺序渲染进度时使用的实际环节编码列表，当前与 {@link #selectedPhases} 相同。 */
    private List<String> phaseOrder;

    /** 以环节编码为键、中文展示名称为值的映射。 */
    private Map<String, String> phaseLabels;
}
