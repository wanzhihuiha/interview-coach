package com.interviewcoach.position.application.dto;

import com.interviewcoach.position.domain.model.PositionProfileData;
import lombok.Data;

/**
 * 岗位画像 HTTP 响应，分别承载已确认的正式画像与当前成功任务产生的候选画像。
 * 普通公共读取只暴露正式画像；个人所有者和公共管理入口可结合任务代次决定确认或重试。
 */
@Data
public class PositionProfileResponse {

    /** 正式画像记录 ID；岗位尚未确认过画像时为空。 */
    private Long profileId;
    /** 画像所属岗位的数据库 ID。 */
    private Long positionId;
    /** 已确认并持久化的正式画像；不存在正式画像时为空，面试流程只使用该字段对应的数据。 */
    private PositionProfileData profile;
    /** 调用方可见的当前任务 ID；无任务或任务细节被隐藏时为空。 */
    private Long taskId;
    /** 可见当前任务的稳定英文状态编码；无可见任务时为空。 */
    private String latestTaskStatus;
    /** 可见当前任务状态的中文展示名称；无可见任务时为空。 */
    private String latestTaskStatusLabel;
    /** 当前任务成功后等待确认的候选画像；与正式画像是两个版本，其他状态或无管理权时为空。 */
    private PositionProfileData candidateProfile;
    /** 可见当前任务失败时的稳定错误分类；其他状态为空。 */
    private String analysisErrorCode;
    /** 可见当前任务失败时的脱敏提示；其他状态为空。 */
    private String analysisErrorMessage;
    /** 是否已经存在可供面试使用的正式画像，不由候选画像是否存在决定。 */
    private Boolean profileUsable;
    /** {@code true} 表示活动岗位存在当前成功候选且调用方有权确认。 */
    private Boolean canConfirm;
    /** {@code true} 表示活动岗位当前无任务或任务已终结且调用方有权重新解析。 */
    private Boolean canRetry;
    /** {@code true} 表示岗位已归档，候选确认和重新解析均不可用。 */
    private Boolean archived;
}
