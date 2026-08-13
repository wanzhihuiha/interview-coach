package com.interviewcoach.interview.domain.repository;

import com.interviewcoach.interview.domain.entity.ThemeEvaluation;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * 遗留主题评估映射的 Spring Data JPA 仓储。
 * 当前主应用没有注入或调用该接口，现行评分和报告不依赖这里的数据。
 */
@Repository
public interface ThemeEvaluationRepository extends JpaRepository<ThemeEvaluation, Long> {

    /** 返回指定面试的全部历史主题评估，不声明排序；当前主流程无调用者。 */
    List<ThemeEvaluation> findByInterviewId(Long interviewId);
}
