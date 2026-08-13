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
 * 岗位实体的 JPA 仓储，为个人、公共、管理员和面试入口提供带资源归属及可见性条件的查询。
 * 调用方仍需在应用层执行状态守卫，仓储查询条件不能被客户端传入的用户或公共标记替代。
 */
@Repository
public interface PositionRepository extends JpaRepository<Position, Long> {

    /** 统计指定用户仍占用个人岗位名额的未归档非公共记录。 */
    long countByUserIdAndIsPublicFalseAndArchivedAtIsNull(Long userId);

    /**
     * 按 ID 悲观写锁读取岗位，供任务状态流转按固定的 Position -> Task 顺序串行化。
     *
     * @return 岗位不存在时为空
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Position p where p.id = :id")
    Optional<Position> findByIdForUpdate(@Param("id") Long id);

    /**
     * 按岗位 ID、用户归属和非公共类型悲观写锁读取个人岗位；是否允许归档状态由具体业务动作判断。
     *
     * @return 不存在、非本人或公共岗位均返回空，避免向调用方区分资源存在性
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Position p where p.id = :id and p.userId = :userId "
            + "and p.isPublic = false")
    Optional<Position> findOwnedPersonalByIdForUpdate(
            @Param("id") Long id, @Param("userId") Long userId);

    /**
     * 按岗位 ID 和公共类型悲观写锁读取公共岗位；是否允许归档状态由具体业务动作判断。
     *
     * @return 不存在或个人岗位时为空
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Position p where p.id = :id and p.isPublic = true")
    Optional<Position> findPublicByIdForUpdate(@Param("id") Long id);

    /** 分页读取指定用户未归档的个人岗位，不包含公共或其他用户记录。 */
    Page<Position> findByUserIdAndIsPublicFalseAndArchivedAtIsNull(
            Long userId, Pageable pageable);

    /** 分页读取指定用户已归档的个人岗位，不包含公共或其他用户记录。 */
    Page<Position> findByUserIdAndIsPublicFalseAndArchivedAtIsNotNull(
            Long userId, Pageable pageable);

    /**
     * 按 ID 和用户归属读取个人岗位，供个人状态入口使用；允许返回归档记录。
     *
     * @return 不存在、非本人或公共岗位时为空
     */
    Optional<Position> findByIdAndUserIdAndIsPublicFalse(Long id, Long userId);

    /**
     * 按 ID 读取公共岗位，供受管理员角色保护的应用入口使用；允许返回归档记录。
     *
     * @return 不存在或个人岗位时为空
     */
    Optional<Position> findByIdAndIsPublicTrue(Long id);

    /**
     * 创建面试时悲观写锁读取本人活动个人岗位或任一活动公共岗位；正式画像条件由同一事务继续复查。
     *
     * @return 岗位不存在、已归档或个人岗位不属于当前用户时为空
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Position p where p.id = :id and p.archivedAt is null and "
            + "((p.isPublic = false and p.userId = :userId) or p.isPublic = true)")
    Optional<Position> findInterviewAccessibleByIdForUpdate(
            @Param("id") Long id, @Param("userId") Long userId);

    /** 分页读取未归档且已有正式画像的公共岗位；没有当前任务也不影响已确认画像的公开可见性。 */
    @Query("select p from Position p where p.isPublic = true and p.archivedAt is null "
            + "and exists (select pp.id from PositionProfile pp where pp.positionId = p.id)")
    Page<Position> findPublishedPublic(Pageable pageable);

    /** 分页读取本人个人岗位与公共岗位中未归档且正式画像可用的记录，不包含其他用户个人岗位。 */
    @Query("select p from Position p where p.archivedAt is null "
            + "and exists (select pp.id from PositionProfile pp where pp.positionId = p.id) "
            + "and ((p.isPublic = false and p.userId = :userId) or p.isPublic = true)")
    Page<Position> findAccessibleByUserId(@Param("userId") Long userId, Pageable pageable);

    /**
     * 用户详情入口读取本人个人岗位或已发布活动公共岗位；本人个人岗位允许查看归档记录。
     *
     * @return 不满足归属、公共发布或活动条件时为空，不向调用方区分具体失败原因
     */
    @Query("select p from Position p where p.id = :id and "
            + "((p.isPublic = false and p.userId = :userId) or "
            + "(p.isPublic = true and p.archivedAt is null and exists "
            + "(select pp.id from PositionProfile pp where pp.positionId = p.id)))")
    Optional<Position> findVisibleById(
            @Param("id") Long id, @Param("userId") Long userId);

    /** 管理端分页读取未归档公共岗位，不按正式画像过滤。 */
    Page<Position> findByIsPublicTrueAndArchivedAtIsNull(Pageable pageable);

    /** 管理端分页读取已归档公共岗位，不包含个人岗位。 */
    Page<Position> findByIsPublicTrueAndArchivedAtIsNotNull(Pageable pageable);
}
