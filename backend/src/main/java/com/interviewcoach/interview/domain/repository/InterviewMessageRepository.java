package com.interviewcoach.interview.domain.repository;

import com.interviewcoach.interview.domain.entity.InterviewMessage;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * 面试消息数据访问层。
 */
@Repository
public interface InterviewMessageRepository extends JpaRepository<InterviewMessage, Long> {

    List<InterviewMessage> findByInterviewIdOrderBySeqNoAsc(Long interviewId);

    List<InterviewMessage> findByInterviewIdAndPhaseOrderBySeqNoAsc(Long interviewId, String phase);

    List<InterviewMessage> findByInterviewIdAndRole(Long interviewId, String role);
}
