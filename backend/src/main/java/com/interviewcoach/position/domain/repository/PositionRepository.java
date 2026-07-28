package com.interviewcoach.position.domain.repository;

import com.interviewcoach.position.domain.entity.Position;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
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
     * 查询公共岗位列表（已审核通过）。
     */
    Page<Position> findByIsPublicTrueAndAuditStatus(
            com.interviewcoach.position.domain.entity.PositionAuditStatus auditStatus,
            Pageable pageable);

    /**
     * 管理员：按审核状态分页查询所有岗位。
     */
    Page<Position> findByAuditStatus(
            com.interviewcoach.position.domain.entity.PositionAuditStatus auditStatus,
            Pageable pageable);
}
