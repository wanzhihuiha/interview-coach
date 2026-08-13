package com.interviewcoach.position.application.dto;

import lombok.Data;

/**
 * 岗位解析状态的轻量轮询响应，由 MySQL 当前任务与正式画像状态生成，并可附带 Redis 等待量快照。
 * 个人入口只返回本人岗位，管理入口只返回公共岗位，前端据此刷新状态和可用操作。
 */
@Data
public class PositionAnalysisStatusResponse {

    /** 被轮询岗位的数据库 ID。 */
    private Long positionId;

    /** 当前任务 ID；岗位尚无任务时为空，确认候选时需要把该代次回传。 */
    private Long taskId;

    /** 当前任务的稳定英文状态编码；岗位尚无任务时为空。 */
    private String latestTaskStatus;

    /** 当前任务状态的中文展示名称；无任务时为空。 */
    private String latestTaskStatusLabel;

    /**
     * Redis 投影在本次读取时估算的前方任务量；任务不在等待队列、恢复未完成或 Redis 不可用时为空。
     * 该值随并发领取和投影状态变化，不代表开始时间或完成时间承诺。
     */
    private Long queueAhead;

    /** 当前任务失败时的稳定错误分类；其他状态为空。 */
    private String analysisErrorCode;

    /** 当前任务失败时可向调用方展示的脱敏说明；其他状态为空。 */
    private String analysisErrorMessage;

    /** 是否已经存在可供详情和面试使用的正式画像，与当前候选任务是否成功相互独立。 */
    private Boolean profileUsable;

    /** {@code true} 表示活动岗位的当前任务已成功并可确认；{@code false} 表示当前不可确认。 */
    private Boolean canConfirm;

    /** {@code true} 表示活动岗位当前无任务或任务已终结，可重新解析；等待、运行或归档时为 {@code false}。 */
    private Boolean canRetry;

    /** {@code true} 表示岗位已归档且不能确认或重试；{@code false} 表示岗位仍处于活动状态。 */
    private Boolean archived;
}
