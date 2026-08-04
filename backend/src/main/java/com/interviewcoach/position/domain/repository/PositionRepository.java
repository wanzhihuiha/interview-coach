package com.interviewcoach.position.domain.repository;

import com.interviewcoach.position.domain.entity.Position;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * 岗位数据访问层。
 */
@Repository
public interface PositionRepository extends JpaRepository<Position, Long> {

    /**
     * 统计用户仍占用名额的未归档个人岗位。
     */
    long countByUserIdAndIsPublicFalseAndArchivedAtIsNull(Long userId);

    /**
     * 锁定岗位，供任务状态流转按固定的 Position -> Task 顺序串行化。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Position p where p.id = :id")
    Optional<Position> findByIdForUpdate(@Param("id") Long id);

    /**
     * 按归属锁定个人岗位；是否允许已归档状态由具体业务动作判断。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Position p where p.id = :id and p.userId = :userId "
            + "and p.isPublic = false")
    Optional<Position> findOwnedPersonalByIdForUpdate(
            @Param("id") Long id, @Param("userId") Long userId);

    /**
     * 锁定公共岗位；是否允许已归档状态由具体业务动作判断。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Position p where p.id = :id and p.isPublic = true")
    Optional<Position> findPublicByIdForUpdate(@Param("id") Long id);

    Page<Position> findByUserIdAndIsPublicFalseAndArchivedAtIsNull(
            Long userId, Pageable pageable);

    Page<Position> findByUserIdAndIsPublicFalseAndArchivedAtIsNotNull(
            Long userId, Pageable pageable);

    Optional<Position> findByIdAndUserIdAndIsPublicFalse(Long id, Long userId);

    Optional<Position> findByIdAndIsPublicTrue(Long id);

    /**
     * 创建面试时锁定本人活动个人岗位或活动公共岗位；正式画像条件由同一事务继续复查。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Position p where p.id = :id and p.archivedAt is null and "
            + "((p.isPublic = false and p.userId = :userId) or p.isPublic = true)")
    Optional<Position> findInterviewAccessibleByIdForUpdate(
            @Param("id") Long id, @Param("userId") Long userId);

    /**
     * 普通用户只能看到未归档且已有正式画像的公共岗位。
     */
    @Query("select p from Position p where p.isPublic = true and p.archivedAt is null "
            + "and exists (select pp.id from PositionProfile pp where pp.positionId = p.id)")
    Page<Position> findPublishedPublic(Pageable pageable);

    /**
     * 首页只组合本人和公共的未归档、正式画像可用岗位。
     */
    @Query("select p from Position p where p.archivedAt is null "
            + "and exists (select pp.id from PositionProfile pp where pp.positionId = p.id) "
            + "and ((p.isPublic = false and p.userId = :userId) or p.isPublic = true)")
    Page<Position> findAccessibleByUserId(@Param("userId") Long userId, Pageable pageable);

    /**
     * 用户详情入口：本人个人岗位可查看归档记录，公共岗位只暴露已发布活动记录。
     */
    @Query("select p from Position p where p.id = :id and "
            + "((p.isPublic = false and p.userId = :userId) or "
            + "(p.isPublic = true and p.archivedAt is null and exists "
            + "(select pp.id from PositionProfile pp where pp.positionId = p.id)))")
    Optional<Position> findVisibleById(
            @Param("id") Long id, @Param("userId") Long userId);

    Page<Position> findByIsPublicTrueAndArchivedAtIsNull(Pageable pageable);

    Page<Position> findByIsPublicTrueAndArchivedAtIsNotNull(Pageable pageable);
}
