package com.interviewcoach.position.application.dto;

import java.time.LocalDateTime;
import lombok.Data;

/**
 * 岗位详情 HTTP 响应，组合岗位记录、当前任务摘要、正式画像可用性与调用方可执行操作。
 * 普通用户只能读取本人岗位或已发布公共岗位；管理员公共入口只读取公共岗位，不由本对象扩大资源权限。
 */
@Data
public class PositionDetailResponse {

    /** 当前详情对应的岗位数据库 ID。 */
    private Long positionId;
    /** 岗位名称。 */
    private String positionName;
    /** 公司名称；原请求未提供时为空。 */
    private String companyName;
    /** 工作地点；原请求未提供时为空。 */
    private String location;
    /** 薪资范围文本；原请求未提供时为空。 */
    private String salaryRange;
    /** 岗位大类的稳定编码或当前存储值。 */
    private String jobCategory;
    /** 根据岗位大类当前映射得到的中文名称；未知历史值按公共映射策略处理。 */
    private String jobCategoryLabel;
    /** 岗位职级编码或当前存储文本；可以为空。 */
    private String level;
    /** 岗位职级的中文展示名称；未知历史值保持原值，职级为空时也为空。 */
    private String levelLabel;
    /** 经服务端规范化并持久化的 JD 正文。 */
    private String jdContent;
    /** {@code true} 表示公共岗位，{@code false} 表示用户个人岗位。 */
    private Boolean isPublic;
    /** 个人岗位所属用户 ID；公共岗位没有所属用户，因此为空。 */
    private Long userId;
    /** {@code true} 表示岗位已归档并停止新的确认、重试和普通公共访问。 */
    private Boolean archived;
    /** 岗位归档时间；活动岗位为空。 */
    private LocalDateTime archivedAt;
    /** 调用方有权查看时的当前任务 ID；无任务或公共普通用户不可见任务细节时为空。 */
    private Long latestTaskId;
    /** 可见当前任务的稳定英文状态编码；无可见任务时为空。 */
    private String latestTaskStatus;
    /** 可见当前任务状态的中文展示名称；无可见任务时为空。 */
    private String latestTaskStatusLabel;
    /** 是否存在已确认的正式画像；该值不等同于当前任务是否成功。 */
    private Boolean profileUsable;
    /** 当前调用场景是否允许确认成功候选；归档、无管理权或任务非成功时为 {@code false}。 */
    private Boolean canConfirm;
    /** 当前调用场景是否允许新建替代任务；归档、无管理权或任务仍等待/运行时为 {@code false}。 */
    private Boolean canRetry;
    /** 当前可见任务失败时的稳定错误分类；其他状态或任务不可见时为空。 */
    private String analysisErrorCode;
    /** 当前可见任务失败时的脱敏提示；其他状态或任务不可见时为空。 */
    private String analysisErrorMessage;
    /** 岗位记录首次持久化时间，由实体创建回调生成。 */
    private LocalDateTime createdAt;
    /** 岗位实体最近一次普通 JPA 更新的时间；批量 JPQL 更新不由该字段承担。 */
    private LocalDateTime updatedAt;
}
