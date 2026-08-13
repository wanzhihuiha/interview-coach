package com.interviewcoach.interview.domain.repository;

import com.interviewcoach.interview.domain.entity.InterviewMessage;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * 面试消息的 Spring Data JPA 仓储。
 *
 * <p>应用服务先通过面试仓储校验用户归属，再使用这里仅含 interviewId 的查询读取问答；
 * 创建和轮次状态服务使用父接口保存首题、回答及下一题。</p>
 */
@Repository
public interface InterviewMessageRepository extends JpaRepository<InterviewMessage, Long> {

    /** 返回指定面试的全部消息，并按面试级序号升序恢复实际对话顺序。 */
    List<InterviewMessage> findByInterviewIdOrderBySeqNoAsc(Long interviewId);

    /** 返回指定面试和环节的消息，并按序号升序；没有匹配项时返回空列表。 */
    List<InterviewMessage> findByInterviewIdAndPhaseOrderBySeqNoAsc(Long interviewId, String phase);

    /**
     * 返回指定面试和角色的消息；方法名没有声明排序，调用方不能依赖数据库返回顺序。
     */
    List<InterviewMessage> findByInterviewIdAndRole(Long interviewId, String role);
}
