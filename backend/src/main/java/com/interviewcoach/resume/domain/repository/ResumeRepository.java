package com.interviewcoach.resume.domain.repository;

import com.interviewcoach.resume.domain.entity.Resume;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * 简历数据访问层。
 */
@Repository
public interface ResumeRepository extends JpaRepository<Resume, Long> {

    /**
     * 分页查询当前用户的简历列表。
     */
    Page<Resume> findByUserId(Long userId, Pageable pageable);

    /**
     * 查询当前用户的指定简历。
     */
    Optional<Resume> findByIdAndUserId(Long id, Long userId);
}
