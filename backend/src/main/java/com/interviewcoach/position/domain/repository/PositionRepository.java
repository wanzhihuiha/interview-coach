package com.interviewcoach.position.domain.repository;

import com.interviewcoach.position.domain.entity.Position;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * 岗位数据访问层。
 */
@Repository
public interface PositionRepository extends JpaRepository<Position, Long> {

    /**
     * 分页查询当前用户的岗位列表。
     */
    Page<Position> findByUserId(Long userId, Pageable pageable);

    /**
     * 按解析状态分页查询当前用户的岗位列表。
     */
    Page<Position> findByUserIdAndParseStatus(Long userId,
                                              com.interviewcoach.position.domain.entity.PositionParseStatus parseStatus,
                                              Pageable pageable);

    /**
     * 按审核状态分页查询当前用户的岗位列表。
     */
    Page<Position> findByUserIdAndAuditStatus(Long userId,
                                              com.interviewcoach.position.domain.entity.PositionAuditStatus auditStatus,
                                              Pageable pageable);

    /**
     * 按解析状态与审核状态分页查询当前用户的岗位列表。
     */
    Page<Position> findByUserIdAndParseStatusAndAuditStatus(
            Long userId,
            com.interviewcoach.position.domain.entity.PositionParseStatus parseStatus,
            com.interviewcoach.position.domain.entity.PositionAuditStatus auditStatus,
            Pageable pageable);

    /**
     * 查询当前用户的指定岗位。
     */
    Optional<Position> findByIdAndUserId(Long id, Long userId);

    /**
     * 查询当前用户可用于面试的指定岗位：本人岗位，或已审核通过的公共岗位。
     */
    @Query("SELECT p FROM Position p WHERE p.id = :id AND (p.userId = :userId "
            + "OR (p.isPublic = true AND p.auditStatus = :approvedStatus))")
    Optional<Position> findAccessibleById(
            @Param("id") Long id,
            @Param("userId") Long userId,
            @Param("approvedStatus") com.interviewcoach.position.domain.entity.PositionAuditStatus approvedStatus);

    /**
     * 查询公共岗位列表（已审核通过）。
     */
    Page<Position> findByIsPublicTrueAndAuditStatus(
            com.interviewcoach.position.domain.entity.PositionAuditStatus auditStatus,
            Pageable pageable);

    /**
     * 查询当前用户可访问的岗位列表（用户自己的岗位 + 已审核通过的公共岗位）。
     */
    @Query("SELECT p FROM Position p WHERE p.userId = :userId "
            + "OR (p.isPublic = true AND p.auditStatus = :approvedStatus)")
    Page<Position> findAccessibleByUserId(
            @Param("userId") Long userId,
            @Param("approvedStatus") com.interviewcoach.position.domain.entity.PositionAuditStatus approvedStatus,
            Pageable pageable);

    /**
     * 管理员：按审核状态分页查询所有岗位。
     */
    Page<Position> findByAuditStatus(
            com.interviewcoach.position.domain.entity.PositionAuditStatus auditStatus,
            Pageable pageable);

    /**
     * 按审核状态统计岗位数量。
     */
    long countByAuditStatus(com.interviewcoach.position.domain.entity.PositionAuditStatus auditStatus);
}
