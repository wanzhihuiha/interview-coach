package com.interviewcoach.position.application.event;

/**
 * MySQL 已登记 WAITING 任务后，请求异步建立 Redis 公平队列投影的轻量事件。
 * 事务内发布时由提交后监听器处理，事务外发布时走回退监听路径；监听失败不改变数据库任务事实。
 *
 * @param taskId 已提交的当前解析任务 ID
 * @param positionId 任务所属岗位 ID，用于异步日志上下文
 * @param queueOwner 服务端生成的公共或个人队列参与者标识
 */
public record PositionAnalysisRequestedEvent(Long taskId, Long positionId, String queueOwner) {
}
