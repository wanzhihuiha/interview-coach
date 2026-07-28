package com.interviewcoach.interview.domain.repository;

import com.interviewcoach.interview.domain.entity.PermanentQuestionBank;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * 永久题库数据访问接口。
 */
@Repository
public interface PermanentQuestionBankRepository extends JpaRepository<PermanentQuestionBank, Long> {

    /**
     * 按岗位类别和环节随机采样题目（MVP 阶段按创建时间倒序取前 N 条）。
     */
    List<PermanentQuestionBank> findByJobCategoryAndPhaseOrderByUsageCountAsc(String jobCategory, String phase);

    /**
     * 按岗位类别、环节、主题查询题目。
     */
    List<PermanentQuestionBank> findByJobCategoryAndPhaseAndTopicIdOrderByUsageCountAsc(
            String jobCategory, String phase, String topicId);

    /**
     * 按内容精确匹配查询。
     */
    List<PermanentQuestionBank> findByContent(String content);

    /**
     * 按岗位类别、环节、主题分页查询永久题库（支持模糊匹配主题或内容）。
     */
    @Query("""
            SELECT p FROM PermanentQuestionBank p
            WHERE (:jobCategory IS NULL OR p.jobCategory = :jobCategory)
              AND (:phase IS NULL OR p.phase = :phase)
              AND (:keyword IS NULL OR p.topicName LIKE %:keyword% OR p.content LIKE %:keyword%)
            ORDER BY p.createdAt DESC
            """)
    Page<PermanentQuestionBank> findPermanentQuestions(
            @Param("jobCategory") String jobCategory,
            @Param("phase") String phase,
            @Param("keyword") String keyword,
            Pageable pageable);
}
