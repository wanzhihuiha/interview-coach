package com.interviewcoach.interview.domain.repository;

import com.interviewcoach.interview.domain.entity.ThemeEvaluation;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * 主题评估数据访问层。
 */
@Repository
public interface ThemeEvaluationRepository extends JpaRepository<ThemeEvaluation, Long> {

    List<ThemeEvaluation> findByInterviewId(Long interviewId);
}
