package com.interviewcoach.resume.domain.repository;

import com.interviewcoach.resume.domain.entity.ResumeProfileDraft;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * 简历画像草稿数据访问层。
 */
@Repository
public interface ResumeProfileDraftRepository extends JpaRepository<ResumeProfileDraft, Long> {

    Optional<ResumeProfileDraft> findByResumeIdAndUserId(Long resumeId, Long userId);
}
