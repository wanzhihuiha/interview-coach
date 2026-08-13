package com.interviewcoach.position.application.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 岗位和当前解析任务在 MySQL 登记成功后的 HTTP 响应。
 * 调用方使用岗位 ID 进入详情，并使用任务 ID 和状态开始轮询；该响应不表示 Redis 投影或模型解析已完成。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PositionCreateResponse {

    /** 新建或重新解析所对应岗位的数据库 ID。 */
    private Long positionId;

    /** 当前岗位名称；重新解析时沿用已登记岗位的名称。 */
    private String positionName;

    /** 本次登记的当前解析任务 ID，供状态轮询和候选确认校验任务代次。 */
    private Long taskId;

    /** 数据库登记完成时任务的稳定英文状态编码，当前创建链路为 WAITING。 */
    private String latestTaskStatus;
}
