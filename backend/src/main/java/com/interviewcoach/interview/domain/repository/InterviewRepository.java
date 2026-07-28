package com.interviewcoach.interview.domain.repository;

import com.interviewcoach.interview.domain.entity.Interview;
import com.interviewcoach.interview.domain.entity.InterviewStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * 面试会话数据访问层。
 */
@Repository
public interface InterviewRepository extends JpaRepository<Interview, Long> {

    Optional<Interview> findByIdAndUserId(Long id, Long userId);

    List<Interview> findByUserIdOrderByCreatedAtDesc(Long userId);

    Page<Interview> findByUserId(Long userId, Pageable pageable);

    List<Interview> findByStatus(InterviewStatus status);
}
