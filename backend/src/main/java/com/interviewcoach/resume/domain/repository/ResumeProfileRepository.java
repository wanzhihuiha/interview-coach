package com.interviewcoach.resume.domain.repository;

import com.interviewcoach.resume.domain.entity.ResumeProfile;
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
 * 简历画像数据访问层。
 */
@Repository
public interface ResumeProfileRepository extends JpaRepository<ResumeProfile, Long> {

    Optional<ResumeProfile> findByResumeIdAndUserId(Long resumeId, Long userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from ResumeProfile p where p.resumeId = :resumeId and p.userId = :userId")
    Optional<ResumeProfile> findByResumeIdAndUserIdForUpdate(
            @Param("resumeId") Long resumeId, @Param("userId") Long userId);

    @Query("select p.resumeId from ResumeProfile p where p.userId = :userId and p.resumeId in :resumeIds")
    List<Long> findResumeIdsByUserIdAndResumeIdIn(
            @Param("userId") Long userId, @Param("resumeIds") Collection<Long> resumeIds);
}
