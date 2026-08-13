package com.interviewcoach.resume.domain.repository;

import com.interviewcoach.resume.domain.entity.Resume;
import com.interviewcoach.resume.domain.entity.ResumeParseStatus;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * 简历主记录的 JPA 仓储，供用户接口、解析状态机和当前进程启动恢复读取数据库事实。
 */
@Repository
public interface ResumeRepository extends JpaRepository<Resume, Long> {

    /**
     * 分页查询当前用户的简历列表。
     */
    Page<Resume> findByUserId(Long userId, Pageable pageable);

    /**
     * 统计当前用户仍保留的简历；当前删除为物理删除，因此无需额外删除条件。
     */
    long countByUserId(Long userId);

    /**
     * 查询当前用户的指定简历。
     */
    Optional<Resume> findByIdAndUserId(Long id, Long userId);

    /** 查询指定解析状态且仍保存额度凭据的简历，供当前进程启动恢复逐项结算。 */
    List<Resume> findByParseStatusInAndParseQuotaDateIsNotNullAndParseQuotaTokenIsNotNull(
            Collection<ResumeParseStatus> statuses);

    /**
     * 锁定当前用户的指定简历，用于串行推进后台解析状态。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from Resume r where r.id = :id and r.userId = :userId")
    Optional<Resume> findByIdAndUserIdForUpdate(@Param("id") Long id, @Param("userId") Long userId);

    /**
     * 将所有遗留的指定解析状态批量标记失败并清除认领时间。
     *
     * <p>该 JPQL 更新绕过实体回调，调用方显式传入更新时间；返回实际受影响行数供恢复日志使用。</p>
     */
    @Modifying
    @Query("update Resume r set r.parseStatus = :failedStatus, r.parseStartedAt = null, "
            + "r.parseErrorCode = :errorCode, r.parseErrorMessage = :errorMessage, r.updatedAt = :updatedAt "
            + "where r.parseStatus in :interruptedStatuses")
    int markInterruptedParsesAsFailed(
            @Param("interruptedStatuses") Collection<ResumeParseStatus> interruptedStatuses,
            @Param("failedStatus") ResumeParseStatus failedStatus,
            @Param("errorCode") String errorCode,
            @Param("errorMessage") String errorMessage,
            @Param("updatedAt") LocalDateTime updatedAt);
}
