package com.interviewcoach.resume.domain.repository;

import com.interviewcoach.resume.domain.entity.ResumeProfile;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * 简历画像数据访问层。
 */
@Repository
public interface ResumeProfileRepository extends JpaRepository<ResumeProfile, Long> {

    /**
     * 根据简历ID查询画像。
     */
    Optional<ResumeProfile> findByResumeId(Long resumeId);
}
