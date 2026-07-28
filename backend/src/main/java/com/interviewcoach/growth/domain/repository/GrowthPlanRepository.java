package com.interviewcoach.growth.domain.repository;

import com.interviewcoach.growth.domain.entity.GrowthPlan;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * 成长方案仓库。
 */
@Repository
public interface GrowthPlanRepository extends JpaRepository<GrowthPlan, Long> {

    Optional<GrowthPlan> findByInterviewId(Long interviewId);

    Optional<GrowthPlan> findByInterviewIdAndUserId(Long interviewId, Long userId);
}
