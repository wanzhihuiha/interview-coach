package com.interviewcoach.interview.application.service;

import com.interviewcoach.interview.domain.entity.Interview;
import com.interviewcoach.interview.domain.entity.InterviewStatus;
import com.interviewcoach.interview.domain.repository.InterviewRepository;
import com.interviewcoach.position.domain.entity.Position;
import com.interviewcoach.position.domain.repository.PositionRepository;
import com.interviewcoach.resume.domain.entity.Resume;
import com.interviewcoach.resume.domain.repository.ResumeRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 应用启动时中断进行中的面试，并清理这些面试占用的简历和岗位。
 *
 * <p>该 Runner 由 Spring Boot 在应用启动后调用，通过面试、简历和岗位仓储直接更新状态与占用。
 * 它没有环境开关，每个应用实例启动时都会处理数据库中的全部 {@code IN_PROGRESS} 面试。
 * 这能清理由重启遗留的占用，但在多实例部署中，新实例启动也可能中断其他实例仍在服务的正常面试。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InterviewLockCleanupRunner implements ApplicationRunner {

    private final InterviewRepository interviewRepository;
    private final ResumeRepository resumeRepository;
    private final PositionRepository positionRepository;

    /**
     * 在应用启动阶段批量中断全部进行中的面试，并释放仍由这些面试持有的资源占用。
     *
     * <p>全部记录在同一个事务中处理，任意一条保存失败都可能使本批修改整体回滚。
     * 当前只把状态改为 {@code INTERRUPTED}，不会补写面试结束时间。</p>
     */
    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        // 1. 启动后一次性读取全部进行中的面试，不区分用户、创建时间或所属应用实例。
        List<Interview> activeInterviews = interviewRepository.findByStatus(InterviewStatus.IN_PROGRESS);
        if (activeInterviews.isEmpty()) {
            return;
        }
        log.info("[InterviewLockCleanup] 发现 {} 个进行中的面试，将标记为中断并释放锁定", activeInterviews.size());
        for (Interview interview : activeInterviews) {
            // 2. 只把面试改为中断状态，不补写结束时间，避免重启后继续沿用原会话。
            interview.setStatus(InterviewStatus.INTERRUPTED);
            interviewRepository.save(interview);

            // 3. 只有资源当前仍由该面试占用时才解锁，避免清掉后来建立的其他面试占用。
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
