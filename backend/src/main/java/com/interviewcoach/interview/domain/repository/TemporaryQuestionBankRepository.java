package com.interviewcoach.interview.domain.repository;

import com.interviewcoach.interview.domain.entity.TemporaryQuestionBank;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * 临时题目的 Spring Data JPA 仓储，供 Evaluator 附带保存、题库去重和管理端审核使用。
 */
@Repository
public interface TemporaryQuestionBankRepository extends JpaRepository<TemporaryQuestionBank, Long> {

    /**
     * 按岗位类别、环节和调用方给出的字符串状态精确查询候选题；是否为 PENDING 由调用方决定。
     */
    List<TemporaryQuestionBank> findByJobCategoryAndPhaseAndStatus(String jobCategory, String phase, String status);

    /**
     * 按正文精确匹配查询，供题库工具跨临时/永久集合检查重复；无匹配返回空列表。
     */
    List<TemporaryQuestionBank> findByContent(String content);

    /**
     * 按调用方给出的字符串审核状态统计题目数量，不校验状态是否属于既有三值。
     */
    long countByStatus(String status);
}
