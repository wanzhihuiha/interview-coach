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
 * 岗位当前解析任务的 JPA 仓储，由状态、生命周期和启动恢复流程读写 MySQL 任务事实。
 * Redis 队列只投影其中的 WAITING 记录，不能代替本仓储判断任务状态或代次。
 */
@Repository
public interface PositionAnalysisTaskRepository extends JpaRepository<PositionAnalysisTask, Long> {

    /**
     * 查询岗位唯一的当前任务。
     *
     * @return 岗位尚无当前任务时为空
     */
    Optional<PositionAnalysisTask> findByPositionId(Long positionId);

    /** 统计指定队列参与者在 MySQL 中处于目标状态的任务数，提交事务用它执行等待上限检查。 */
    long countByQueueOwnerAndStatus(String queueOwner, PositionAnalysisTaskStatus status);

    /** 按任务 ID 升序读取指定状态的任务，供需要稳定顺序的任务扫描使用；没有匹配记录时返回空列表。 */
    List<PositionAnalysisTask> findByStatusOrderByIdAsc(PositionAnalysisTaskStatus status);

    /** 按任务 ID 升序读取全部当前任务，供启动恢复建立稳定数据库快照；没有任务时返回空列表。 */
    List<PositionAnalysisTask> findAllByOrderByIdAsc();

    /** 批量读取给定岗位集合的当前任务，供列表避免逐项查询；未命中的岗位不会产生占位记录。 */
    List<PositionAnalysisTask> findByPositionIdIn(Collection<Long> positionIds);

    /**
     * 悲观写锁读取指定岗位的当前任务；除清理已不存在岗位的孤儿任务外，调用方必须先锁 Position。
     *
     * @return 当前任务不存在时为空，空结果不会创建任务或持有任务行锁
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from PositionAnalysisTask t where t.positionId = :positionId")
    Optional<PositionAnalysisTask> findByPositionIdForUpdate(@Param("positionId") Long positionId);

    /**
     * 仅把 ID、岗位和 WAITING 源状态均匹配的当前任务原子领取为 RUNNING，并显式维护批量更新绕过的时间列。
     *
     * @return {@code 1} 表示本次完成 WAITING 到 RUNNING 转换；{@code 0} 表示任务已过期、不属于该岗位或源状态已变化
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
     * 仅把 ID、岗位和指定源状态均匹配的任务写为成功，防止重复或迟到 Worker 覆盖当前事实。
     *
     * @return {@code 1} 表示候选画像和终态已写入；{@code 0} 表示任务已过期或源状态不再匹配
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
     * 仅把 ID、岗位和指定源状态均匹配的任务写为失败，错误字段只保存稳定代码和脱敏说明。
     *
     * @return {@code 1} 表示失败终态已写入；{@code 0} 表示任务已过期或源状态不再匹配
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
