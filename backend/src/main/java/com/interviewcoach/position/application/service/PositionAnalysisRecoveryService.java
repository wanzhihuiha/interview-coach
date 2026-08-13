package com.interviewcoach.position.application.service;

import com.interviewcoach.position.domain.entity.Position;
import com.interviewcoach.position.domain.entity.PositionAnalysisTask;
import com.interviewcoach.position.domain.entity.PositionAnalysisTaskStatus;
import com.interviewcoach.position.domain.exception.PositionAnalysisFailureCode;
import com.interviewcoach.position.domain.model.PositionAnalysisQueueOwner;
import com.interviewcoach.position.domain.repository.PositionAnalysisTaskRepository;
import com.interviewcoach.position.domain.repository.PositionRepository;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 当前应用进程启动时收口 MySQL 中遗留的岗位任务，并生成 Redis 重建所需的 WAITING 快照。
 * 启动 Runner 在开放本 JVM 的调度器前调用本服务；当前源码没有提供多实例恢复租约或单实例部署保证，
 * 因此本服务只描述当前进程执行的恢复步骤，不把部署拓扑当作已确认前提。
 */
@Service
@RequiredArgsConstructor
public class PositionAnalysisRecoveryService {

    /** 按固定的 Position 再 Task 顺序锁定岗位事实，避免恢复过程与状态流转使用相反锁序。 */
    private final PositionRepository positionRepository;
    /** 读取并收口 MySQL 当前任务，最终产出可重建 Redis 投影的 WAITING 集合。 */
    private final PositionAnalysisTaskRepository taskRepository;

    /**
     * 按 taskId 扫描当前任务；每项仍按 Position -> Task 锁顺序处理，不自动重跑 RUNNING。
     * 岗位缺失或已归档的任务会删除，遗留 RUNNING 和归属无效的 WAITING 会写为 FAILED，
     * 其余有效 WAITING 仅进入返回快照。整个扫描使用调用本方法时加入的默认 REQUIRED 事务，
     * 并不强制创建独立事务。
     *
     * @return Redis 重建输入及本轮各类收口数量；返回后仍需 Runner 完成 Redis 重建
     */
    @Transactional
    public RecoveryResult recover() {
        // 先按任务 ID 取得稳定扫描快照，后续每项再重新加锁确认它仍是该岗位的当前任务。
        List<PositionAnalysisTask> snapshots = taskRepository.findAllByOrderByIdAsc();
        List<WaitingTask> waitingTasks = new ArrayList<>();
        int interruptedCount = 0;
        int removedCount = 0;
        int invalidCount = 0;
        LocalDateTime now = LocalDateTime.now();

        for (PositionAnalysisTask snapshot : snapshots) {
            // 先锁岗位再锁当前任务，与正常领取、完成和生命周期事务保持一致锁序。
            Position position = positionRepository.findByIdForUpdate(snapshot.getPositionId())
                    .orElse(null);
            PositionAnalysisTask current = taskRepository
                    .findByPositionIdForUpdate(snapshot.getPositionId())
                    .orElse(null);
            if (current == null || !Objects.equals(current.getId(), snapshot.getId())) {
                continue;
            }
            if (position == null || position.isArchived()) {
                // 已无可运行岗位时删除任务事实，避免它进入稍后的 Redis 恢复快照。
                taskRepository.delete(current);
                removedCount++;
                continue;
            }

            if (current.getStatus() == PositionAnalysisTaskStatus.RUNNING) {
                // 上个进程的模型调用结果无法再确认，明确失败并要求用户重新解析，不自动重放远程调用。
                markFailed(
                        current,
                        PositionAnalysisFailureCode.APPLICATION_RESTARTED,
                        "应用重启导致岗位解析中断，请重新解析",
                        now);
                interruptedCount++;
                continue;
            }
            if (current.getStatus() != PositionAnalysisTaskStatus.WAITING) {
                continue;
            }

            String expectedOwner = expectedOwner(position);
            if (expectedOwner == null || !Objects.equals(expectedOwner, current.getQueueOwner())) {
                // 队列归属无法由当前岗位事实重建时将任务收口为失败，防止投影到错误参与者。
                markFailed(
                        current,
                        PositionAnalysisFailureCode.QUEUE_RESERVATION_INVALID,
                        "任务排队归属无效，请重新解析",
                        now);
                invalidCount++;
                continue;
            }
            waitingTasks.add(new WaitingTask(current.getId(), current.getQueueOwner()));
        }
        // 在返回 Redis 快照前把删除和失败变更写入数据库，确保后续重建依据的是已收口事实。
        taskRepository.flush();
        return new RecoveryResult(
                List.copyOf(waitingTasks), interruptedCount, removedCount, invalidCount);
    }

    /**
     * 根据岗位类型推导服务端队列参与者；个人岗位缺少合法所有者时返回空，由恢复流程写为失败。
     */
    private String expectedOwner(Position position) {
        if (Boolean.TRUE.equals(position.getIsPublic())) {
            return PositionAnalysisQueueOwner.PUBLIC;
        }
        try {
            return PositionAnalysisQueueOwner.forUser(position.getUserId());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** 将无法继续运行的任务写为脱敏失败终态，并清除可能残留的候选画像。 */
    private void markFailed(
            PositionAnalysisTask task,
            PositionAnalysisFailureCode failureCode,
            String message,
            LocalDateTime finishedAt) {
        task.setStatus(PositionAnalysisTaskStatus.FAILED);
        task.setCandidateProfileData(null);
        task.setErrorCode(failureCode.name());
        task.setErrorMessage(message);
        task.setFinishedAt(finishedAt);
        taskRepository.save(task);
    }

    /**
     * 当前进程启动恢复的数据库结果，供 Runner 记录指标并重建 Redis 队列。
     *
     * @param waitingTasks 仍有效且需重新投影的 WAITING 任务，顺序沿用任务 ID 扫描顺序
     * @param interruptedCount 从遗留 RUNNING 收口为重启失败的数量
     * @param removedCount 因岗位不存在或已归档而删除的任务数量
     * @param invalidCount 因队列归属无效而写为失败的 WAITING 数量
     */
    public record RecoveryResult(
            List<WaitingTask> waitingTasks,
            int interruptedCount,
            int removedCount,
            int invalidCount) {
    }

    /**
     * Redis 重建所需的最小任务数据，不携带 JD、候选画像或请求用户信息。
     *
     * @param taskId MySQL 当前任务 ID，同时决定参与者内恢复顺序
     * @param queueOwner 经岗位事实核验后的 Redis 队列参与者
     */
    public record WaitingTask(Long taskId, String queueOwner) {
    }
}
