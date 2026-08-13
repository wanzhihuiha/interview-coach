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
 * 永久题目的 Spring Data JPA 仓储，供面试官读取候选题和管理端筛选、晋升去重。
 */
@Repository
public interface PermanentQuestionBankRepository extends JpaRepository<PermanentQuestionBank, Long> {

    /**
     * 按岗位类别和环节返回全部题目，并按 {@code usageCount} 升序；并非随机或创建时间倒序。
     * 当前出题链路再在内存中截取前 N 条，且不会累加使用次数。
     */
    List<PermanentQuestionBank> findByJobCategoryAndPhaseOrderByUsageCountAsc(String jobCategory, String phase);

    /**
     * 按岗位类别、环节和主题精确筛选，并按使用次数升序返回全部匹配题目。
     */
    List<PermanentQuestionBank> findByJobCategoryAndPhaseAndTopicIdOrderByUsageCountAsc(
            String jobCategory, String phase, String topicId);

    /** 按正文精确匹配，供临时保存和管理员晋升时检查重复；无匹配返回空列表。 */
    List<PermanentQuestionBank> findByContent(String content);

    /**
     * 管理端按可选岗位类别、环节和关键字分页筛选永久题库。
     * 关键字对主题名或正文做包含匹配，结果固定按创建时间倒序后再应用分页。
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
