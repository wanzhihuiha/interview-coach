package com.interviewcoach.growth.domain.repository;

import com.interviewcoach.growth.domain.entity.GrowthPlan;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * 成长方案实体的 Spring Data JPA 仓储。
 *
 * <p>成长应用服务通过带用户条件的查询实现资源隔离，并使用继承的 {@code save} 写入生成状态、
 * Markdown 与结构化 JSON。只按面试查询的方法本身不校验用户归属，调用方必须先建立可信边界。</p>
 */
@Repository
public interface GrowthPlanRepository extends JpaRepository<GrowthPlan, Long> {

    /**
     * 只按来源面试读取成长方案；返回空表示该面试尚无记录。
     *
     * <p>此方法不包含 userId 条件，当前成长方案主流程不使用它；其他调用方若使用，必须先完成
     * 面试归属校验，不能把面试 ID 本身当作授权依据。</p>
     */
    Optional<GrowthPlan> findByInterviewId(Long interviewId);

    /**
     * 按来源面试和所属用户联合读取成长方案，供认证用户的获取或生成入口隔离缓存记录。
     *
     * @return 同时匹配面试与用户的唯一记录；不存在或不属于该用户时为空
     */
    Optional<GrowthPlan> findByInterviewIdAndUserId(Long interviewId, Long userId);
}
