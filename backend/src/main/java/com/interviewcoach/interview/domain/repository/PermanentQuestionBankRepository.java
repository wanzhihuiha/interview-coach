package com.interviewcoach.interview.domain.repository;

import com.interviewcoach.interview.domain.entity.PermanentQuestionBank;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
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
}
