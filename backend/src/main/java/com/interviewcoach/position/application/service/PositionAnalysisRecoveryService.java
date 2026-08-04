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
 * 单实例启动时收口上个进程遗留的任务，并生成 Redis 重建所需的 WAITING 快照。
 */
@Service
@RequiredArgsConstructor
public class PositionAnalysisRecoveryService {

    private final PositionRepository positionRepository;
    private final PositionAnalysisTaskRepository taskRepository;

    /**
     * 按 taskId 扫描当前任务；每项仍按 Position -> Task 锁顺序处理，不自动重跑 RUNNING。
     */
    @Transactional
    public RecoveryResult recover() {
        List<PositionAnalysisTask> snapshots = taskRepository.findAllByOrderByIdAsc();
        List<WaitingTask> waitingTasks = new ArrayList<>();
        int interruptedCount = 0;
        int removedCount = 0;
        int invalidCount = 0;
        LocalDateTime now = LocalDateTime.now();

        for (PositionAnalysisTask snapshot : snapshots) {
            Position position = positionRepository.findByIdForUpdate(snapshot.getPositionId())
                    .orElse(null);
            PositionAnalysisTask current = taskRepository
                    .findByPositionIdForUpdate(snapshot.getPositionId())
                    .orElse(null);
            if (current == null || !Objects.equals(current.getId(), snapshot.getId())) {
                continue;
            }
            if (position == null || position.isArchived()) {
                taskRepository.delete(current);
                removedCount++;
                continue;
            }

            if (current.getStatus() == PositionAnalysisTaskStatus.RUNNING) {
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
        taskRepository.flush();
        return new RecoveryResult(
                List.copyOf(waitingTasks), interruptedCount, removedCount, invalidCount);
    }

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

    public record RecoveryResult(
            List<WaitingTask> waitingTasks,
            int interruptedCount,
            int removedCount,
            int invalidCount) {
    }

    public record WaitingTask(Long taskId, String queueOwner) {
    }
}
