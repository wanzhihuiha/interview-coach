package com.interviewcoach.interview.domain.repository;

import com.interviewcoach.interview.domain.entity.InterviewReport;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * 已生成面试报告的 Spring Data JPA 仓储；父接口负责首次缓存写入。
 */
@Repository
public interface InterviewReportRepository extends JpaRepository<InterviewReport, Long> {

    /**
     * 按唯一面试 ID 读取缓存报告，未生成时返回空；调用前必须另行校验面试属于当前用户。
     */
    Optional<InterviewReport> findByInterviewId(Long interviewId);
}
