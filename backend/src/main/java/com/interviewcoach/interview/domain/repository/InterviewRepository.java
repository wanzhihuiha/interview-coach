package com.interviewcoach.interview.domain.repository;

import com.interviewcoach.interview.domain.entity.Interview;
import com.interviewcoach.interview.domain.entity.InterviewStatus;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * 面试会话数据访问层。
 */
@Repository
public interface InterviewRepository extends JpaRepository<Interview, Long> {

    Optional<Interview> findByIdAndUserId(Long id, Long userId);

    /**
     * 锁定本人面试，供首题写回、失败补偿和结束流程串行化状态变更。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from Interview i where i.id = :id and i.userId = :userId")
    Optional<Interview> findByIdAndUserIdForUpdate(
            @Param("id") Long id, @Param("userId") Long userId);

    boolean existsByIdAndStatus(Long id, InterviewStatus status);

    List<Interview> findByUserIdOrderByCreatedAtDesc(Long userId);

    Page<Interview> findByUserId(Long userId, Pageable pageable);

    List<Interview> findByStatus(InterviewStatus status);

    /**
     * 永久删除岗位前检查是否仍有进行中的面试引用它。
     */
    boolean existsByPositionIdAndStatus(Long positionId, InterviewStatus status);
}
