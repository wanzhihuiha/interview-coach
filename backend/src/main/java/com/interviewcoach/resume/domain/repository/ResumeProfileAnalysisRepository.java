package com.interviewcoach.resume.domain.repository;

import com.interviewcoach.resume.domain.entity.ResumeProfileAnalysis;
import com.interviewcoach.resume.domain.entity.ResumeProfileAnalysisStatus;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * 简历画像 AI 分析数据访问层。
 */
@Repository
public interface ResumeProfileAnalysisRepository extends JpaRepository<ResumeProfileAnalysis, Long> {

    Optional<ResumeProfileAnalysis> findByResumeIdAndUserId(Long resumeId, Long userId);

    List<ResumeProfileAnalysis> findByStatusInAndTaskQuotaDateIsNotNullAndTaskQuotaTokenIsNotNull(
            Collection<ResumeProfileAnalysisStatus> statuses);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from ResumeProfileAnalysis a where a.resumeId = :resumeId and a.userId = :userId")
    Optional<ResumeProfileAnalysis> findByResumeIdAndUserIdForUpdate(
            @Param("resumeId") Long resumeId, @Param("userId") Long userId);

    @Modifying
    @Query("update ResumeProfileAnalysis a set a.status = :failedStatus, a.usableForInterview = false, "
            + "a.errorCode = :errorCode, a.errorMessage = :errorMessage, a.updatedAt = :updatedAt "
            + "where a.status in :interruptedStatuses")
    int markInterruptedAsFailed(
            @Param("interruptedStatuses") Collection<ResumeProfileAnalysisStatus> interruptedStatuses,
            @Param("failedStatus") ResumeProfileAnalysisStatus failedStatus,
            @Param("errorCode") String errorCode,
            @Param("errorMessage") String errorMessage,
            @Param("updatedAt") LocalDateTime updatedAt);
}
