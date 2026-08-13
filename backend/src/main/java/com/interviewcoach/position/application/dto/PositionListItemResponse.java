package com.interviewcoach.position.application.dto;

import java.time.LocalDateTime;
import lombok.Data;

/**
 * 岗位分页列表中的单条响应，组合岗位基础信息、正式画像可用性和可见任务摘要。
 * 公共普通列表隐藏任务细节；个人列表保留本人任务与归档信息，管理员列表保留公共岗位管理状态。
 */
@Data
public class PositionListItemResponse {

    /** 列表项对应的岗位数据库 ID。 */
    private Long positionId;
    /** 岗位名称。 */
    private String positionName;
    /** 公司名称；未登记时为空。 */
    private String companyName;
    /** 岗位大类的稳定编码或当前存储值。 */
    private String jobCategory;
    /** 岗位大类的中文展示名称。 */
    private String jobCategoryLabel;
    /** 岗位职级编码或当前存储文本；未登记时为空。 */
    private String level;
    /** 职级中文展示名称；未知值保持原值，职级为空时也为空。 */
    private String levelLabel;
    /** 经规范化并持久化的 JD 正文。 */
    private String jdContent;
    /** {@code true} 表示公共岗位，{@code false} 表示个人岗位。 */
    private Boolean isPublic;
    /** 个人岗位所属用户 ID；公共岗位为空。 */
    private Long userId;
    /** {@code true} 表示该岗位属于当前查询选择的归档记录。 */
    private Boolean archived;
    /** 归档发生时间；活动岗位为空。 */
    private LocalDateTime archivedAt;
    /** 调用方有管理权时的当前任务 ID；公共普通列表隐藏该值。 */
    private Long latestTaskId;
    /** 可见任务的稳定英文状态编码；任务被隐藏或不存在时为空。 */
    private String latestTaskStatus;
    /** 可见任务状态的中文展示名称；任务被隐藏或不存在时为空。 */
    private String latestTaskStatusLabel;
    /** 是否已有正式画像可供详情和面试读取。 */
    private Boolean profileUsable;
    /** 当前调用方是否能确认该岗位的成功候选画像。 */
    private Boolean canConfirm;
    /** 当前调用方是否能为该活动岗位发起新的解析任务。 */
    private Boolean canRetry;
    /** 可见任务失败时的稳定错误分类；其他情况为空。 */
    private String analysisErrorCode;
    /** 可见任务失败时的脱敏提示；其他情况为空。 */
    private String analysisErrorMessage;
    /** 岗位记录创建时间。 */
    private LocalDateTime createdAt;
    /** 岗位实体最近一次普通 JPA 更新时间。 */
    private LocalDateTime updatedAt;
}
