package com.interviewcoach.position.domain.repository;

import com.interviewcoach.position.domain.entity.PositionProfile;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * 岗位画像数据访问层。
 */
@Repository
public interface PositionProfileRepository extends JpaRepository<PositionProfile, Long> {

    /**
     * 根据岗位ID查询画像。
     */
    Optional<PositionProfile> findByPositionId(Long positionId);

    List<PositionProfile> findByPositionIdIn(Collection<Long> positionIds);

    boolean existsByPositionId(Long positionId);

    /**
     * 锁定岗位正式画像，供候选确认事务执行更新或创建。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from PositionProfile p where p.positionId = :positionId")
    Optional<PositionProfile> findByPositionIdForUpdate(@Param("positionId") Long positionId);
}
