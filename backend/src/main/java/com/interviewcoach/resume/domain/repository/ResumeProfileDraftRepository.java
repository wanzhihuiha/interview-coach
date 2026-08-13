package com.interviewcoach.resume.domain.repository;

import com.interviewcoach.resume.domain.entity.ResumeProfileDraft;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * 当前事实画像草稿的 JPA 仓储，供详情、编辑和确认流程按资源归属读取每份简历的单行草稿。
 */
@Repository
public interface ResumeProfileDraftRepository extends JpaRepository<ResumeProfileDraft, Long> {

    /** 按简历和所属用户查询当前草稿；不存在或不归属时返回空结果。 */
    Optional<ResumeProfileDraft> findByResumeIdAndUserId(Long resumeId, Long userId);
}
