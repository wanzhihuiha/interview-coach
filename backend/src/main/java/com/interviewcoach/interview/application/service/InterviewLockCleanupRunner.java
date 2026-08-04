package com.interviewcoach.interview.application.service;

import com.interviewcoach.interview.domain.entity.Interview;
import com.interviewcoach.interview.domain.entity.InterviewStatus;
import com.interviewcoach.interview.domain.repository.InterviewRepository;
import com.interviewcoach.position.domain.entity.Position;
import com.interviewcoach.position.domain.repository.PositionRepository;
import com.interviewcoach.resume.domain.entity.Resume;
import com.interviewcoach.resume.domain.repository.ResumeRepository;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 应用启动时清理面试相关的简历/岗位锁定。
 * 开发/联调过程中后端重启后，进行中的面试会变为僵尸状态，其持有的锁会导致用户无法创建新面试。
 * 该 Runner 在启动后将所有 IN_PROGRESS 面试标记为 INTERRUPTED，并释放对应锁定。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InterviewLockCleanupRunner implements ApplicationRunner {

    private final InterviewRepository interviewRepository;
    private final ResumeRepository resumeRepository;
    private final PositionRepository positionRepository;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        List<Interview> activeInterviews = interviewRepository.findByStatus(InterviewStatus.IN_PROGRESS);
        if (activeInterviews.isEmpty()) {
            return;
        }
        log.info("[InterviewLockCleanup] 发现 {} 个进行中的面试，将标记为中断并释放锁定", activeInterviews.size());
        for (Interview interview : activeInterviews) {
            interview.setStatus(InterviewStatus.INTERRUPTED);
            interview.setEndedAt(LocalDateTime.now());
            interviewRepository.save(interview);

            Resume resume = resumeRepository.findById(interview.getResumeId()).orElse(null);
            if (resume != null && interview.getId().equals(resume.getLockInterviewId())) {
                resume.setLockInterviewId(null);
                resumeRepository.save(resume);
            }
            Position position = positionRepository.findById(interview.getPositionId()).orElse(null);
            if (position != null && interview.getId().equals(position.getLockInterviewId())) {
                position.setLockInterviewId(null);
                positionRepository.save(position);
            }
            log.info("[InterviewLockCleanup] 已释放 interviewId={} 的简历/岗位锁定", interview.getId());
        }
    }
}
