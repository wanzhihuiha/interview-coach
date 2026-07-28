package com.interviewcoach.interview.domain.repository;

import com.interviewcoach.interview.domain.entity.InterviewReport;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * 面试评估报告仓储。
 */
@Repository
public interface InterviewReportRepository extends JpaRepository<InterviewReport, Long> {

    Optional<InterviewReport> findByInterviewId(Long interviewId);
}
