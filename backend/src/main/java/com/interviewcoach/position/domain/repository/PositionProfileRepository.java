package com.interviewcoach.position.domain.repository;

import com.interviewcoach.position.domain.entity.PositionProfile;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
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
}
