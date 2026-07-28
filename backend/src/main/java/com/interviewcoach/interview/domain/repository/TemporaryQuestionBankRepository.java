package com.interviewcoach.interview.domain.repository;

import com.interviewcoach.interview.domain.entity.TemporaryQuestionBank;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * 临时题库数据访问接口。
 */
@Repository
public interface TemporaryQuestionBankRepository extends JpaRepository<TemporaryQuestionBank, Long> {

    /**
     * 按岗位类别和环节查询待审核题目。
     */
    List<TemporaryQuestionBank> findByJobCategoryAndPhaseAndStatus(String jobCategory, String phase, String status);

    /**
     * 按内容精确匹配查询。
     */
    List<TemporaryQuestionBank> findByContent(String content);

    /**
     * 按审核状态统计题目数量。
     */
    long countByStatus(String status);
}
