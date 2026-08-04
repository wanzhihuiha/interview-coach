package com.interviewcoach.position.domain.repository;

import com.interviewcoach.position.domain.entity.PositionAnalysisTask;
import com.interviewcoach.position.domain.entity.PositionAnalysisTaskStatus;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * 岗位当前解析任务数据访问层。
 */
@Repository
public interface PositionAnalysisTaskRepository extends JpaRepository<PositionAnalysisTask, Long> {

    Optional<PositionAnalysisTask> findByPositionId(Long positionId);

    long countByQueueOwnerAndStatus(String queueOwner, PositionAnalysisTaskStatus status);

    List<PositionAnalysisTask> findByStatusOrderByIdAsc(PositionAnalysisTaskStatus status);

    List<PositionAnalysisTask> findAllByOrderByIdAsc();

    List<PositionAnalysisTask> findByPositionIdIn(Collection<Long> positionIds);

    /**
     * 锁定指定岗位的当前任务；除清理已不存在岗位的孤儿任务外，调用方必须先锁 Position。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from PositionAnalysisTask t where t.positionId = :positionId")
    Optional<PositionAnalysisTask> findByPositionIdForUpdate(@Param("positionId") Long positionId);

    /**
     * 仅把仍在等待的当前任务原子领取为运行中，并显式维护批量更新绕过的审计时间。
     */
    @Modifying(flushAutomatically = true)
    @Query("update PositionAnalysisTask t set t.status = :runningStatus, "
            + "t.startedAt = :startedAt, t.finishedAt = null, t.errorCode = null, "
            + "t.errorMessage = null, t.updatedAt = :updatedAt "
            + "where t.id = :taskId and t.positionId = :positionId and t.status = :waitingStatus")
    int markRunning(
            @Param("taskId") Long taskId,
            @Param("positionId") Long positionId,
            @Param("waitingStatus") PositionAnalysisTaskStatus waitingStatus,
            @Param("runningStatus") PositionAnalysisTaskStatus runningStatus,
            @Param("startedAt") LocalDateTime startedAt,
            @Param("updatedAt") LocalDateTime updatedAt);

    /**
     * 仅把仍在指定源状态的任务写成成功，防止重复或迟到 Worker 覆盖当前事实。
     */
    @Modifying(flushAutomatically = true)
    @Query("update PositionAnalysisTask t set t.status = :succeededStatus, "
            + "t.candidateProfileData = :candidateProfileData, t.errorCode = null, "
            + "t.errorMessage = null, t.finishedAt = :finishedAt, t.updatedAt = :updatedAt "
            + "where t.id = :taskId and t.positionId = :positionId and t.status = :expectedStatus")
    int markSucceeded(
            @Param("taskId") Long taskId,
            @Param("positionId") Long positionId,
            @Param("expectedStatus") PositionAnalysisTaskStatus expectedStatus,
            @Param("succeededStatus") PositionAnalysisTaskStatus succeededStatus,
            @Param("candidateProfileData") String candidateProfileData,
            @Param("finishedAt") LocalDateTime finishedAt,
            @Param("updatedAt") LocalDateTime updatedAt);

    /**
     * 仅把仍在指定源状态的任务写成失败，错误字段只保存稳定代码和脱敏说明。
     */
    @Modifying(flushAutomatically = true)
    @Query("update PositionAnalysisTask t set t.status = :failedStatus, "
            + "t.candidateProfileData = null, t.errorCode = :errorCode, "
            + "t.errorMessage = :errorMessage, t.finishedAt = :finishedAt, "
            + "t.updatedAt = :updatedAt where t.id = :taskId and t.positionId = :positionId "
            + "and t.status = :expectedStatus")
    int markFailed(
            @Param("taskId") Long taskId,
            @Param("positionId") Long positionId,
            @Param("expectedStatus") PositionAnalysisTaskStatus expectedStatus,
            @Param("failedStatus") PositionAnalysisTaskStatus failedStatus,
            @Param("errorCode") String errorCode,
            @Param("errorMessage") String errorMessage,
            @Param("finishedAt") LocalDateTime finishedAt,
            @Param("updatedAt") LocalDateTime updatedAt);
}
