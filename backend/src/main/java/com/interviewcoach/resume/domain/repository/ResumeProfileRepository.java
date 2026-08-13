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
 * 正式事实画像的 JPA 仓储，供确认、查询以及面试创建流程按简历和用户归属读取。
 */
@Repository
public interface ResumeProfileRepository extends JpaRepository<ResumeProfile, Long> {

    /** 按简历和所属用户查询正式画像；尚未确认或不归属时返回空结果。 */
    Optional<ResumeProfile> findByResumeIdAndUserId(Long resumeId, Long userId);

    /** 在事务内按简历和所属用户取得正式画像悲观写锁；不存在时返回空结果。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from ResumeProfile p where p.resumeId = :resumeId and p.userId = :userId")
    Optional<ResumeProfile> findByResumeIdAndUserIdForUpdate(
            @Param("resumeId") Long resumeId, @Param("userId") Long userId);

    /**
     * 批量返回指定用户在候选简历 ID 中已有正式画像的 ID 集合，供列表组装存在性标志。
     *
     * @return 只包含匹配正式画像的简历 ID；无匹配时为空列表
     */
    @Query("select p.resumeId from ResumeProfile p where p.userId = :userId and p.resumeId in :resumeIds")
    List<Long> findResumeIdsByUserIdAndResumeIdIn(
            @Param("userId") Long userId, @Param("resumeIds") Collection<Long> resumeIds);
}
