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
 * 当前应用进程启动完成后清理数据库中的进行中面试及其资源锁。
 *
 * <p>该组件没有环境条件注解，因此只要当前 Spring 应用装配到它就会执行：它会查询全部
 * {@link InterviewStatus#IN_PROGRESS} 记录、标记为中断，并在锁 ID 精确匹配时释放简历和岗位。
 * 当前代码没有实例所有权或单实例守卫，部署是否保证单实例证据不足。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InterviewLockCleanupRunner implements ApplicationRunner {

    /** 负责查询全部进行中面试并保存中断状态。 */
    private final InterviewRepository interviewRepository;

    /** 负责读取并精确释放面试占用的简历锁。 */
    private final ResumeRepository resumeRepository;

    /** 负责读取并精确释放面试占用的岗位锁。 */
    private final PositionRepository positionRepository;

    /**
     * 在当前进程的 Spring Boot 启动回调中执行全量清理。
     *
     * <p>每条记录的状态更新和两类锁释放位于同一事务；任一未处理异常会回滚本次回调中的
     * 数据库写入并继续导致启动失败，而不是保留部分清理结果。</p>
     */
    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        // 从共享数据库读取全部进行中记录；查询不区分创建实例或进程所有者。
        List<Interview> activeInterviews = interviewRepository.findByStatus(InterviewStatus.IN_PROGRESS);
        if (activeInterviews.isEmpty()) {
            return;
        }
        log.info("[InterviewLockCleanup] 发现 {} 个进行中的面试，将标记为中断并释放锁定", activeInterviews.size());
        for (Interview interview : activeInterviews) {
            // 先把会话改为中断终态，使随后创建流程不再把该锁视为活跃面试。
            interview.setStatus(InterviewStatus.INTERRUPTED);
            interview.setEndedAt(LocalDateTime.now());
            interviewRepository.save(interview);

            // 只有简历锁仍由当前面试持有时才清除，避免释放已被其他流程改写的锁。
            Resume resume = resumeRepository.findById(interview.getResumeId()).orElse(null);
            if (resume != null && interview.getId().equals(resume.getLockInterviewId())) {
                resume.setLockInterviewId(null);
                resumeRepository.save(resume);
            }
            // 公共岗位通常没有锁；私有岗位同样通过持有者 ID 精确判断后释放。
            Position position = positionRepository.findById(interview.getPositionId()).orElse(null);
            if (position != null && interview.getId().equals(position.getLockInterviewId())) {
                position.setLockInterviewId(null);
                positionRepository.save(position);
            }
            log.info("[InterviewLockCleanup] 已释放 interviewId={} 的简历/岗位锁定", interview.getId());
        }
    }
}
