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
 * 辅助分析单行记录的 JPA 仓储，供状态服务读取保留结果、推进最新任务并执行启动恢复。
 */
@Repository
public interface ResumeProfileAnalysisRepository extends JpaRepository<ResumeProfileAnalysis, Long> {

    /** 按简历和所属用户查询辅助分析；不存在时返回空结果，不泄露其他用户记录。 */
    Optional<ResumeProfileAnalysis> findByResumeIdAndUserId(Long resumeId, Long userId);

    /** 查询指定任务状态且仍保存额度凭据的分析记录，供当前进程启动恢复逐项结算。 */
    List<ResumeProfileAnalysis> findByStatusInAndTaskQuotaDateIsNotNullAndTaskQuotaTokenIsNotNull(
            Collection<ResumeProfileAnalysisStatus> statuses);

    /** 在事务内按简历和所属用户取得悲观写锁；不存在或不归属时返回空结果。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from ResumeProfileAnalysis a where a.resumeId = :resumeId and a.userId = :userId")
    Optional<ResumeProfileAnalysis> findByResumeIdAndUserIdForUpdate(
            @Param("resumeId") Long resumeId, @Param("userId") Long userId);

    /**
     * 将所有遗留的指定运行中状态批量标记失败并停用面试资格。
     *
     * <p>该 JPQL 更新绕过实体回调，调用方显式传入更新时间；返回实际受影响行数供恢复日志使用。</p>
     */
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
