package com.interviewcoach.position.application.service;

import com.interviewcoach.position.application.event.PositionAnalysisRequestedEvent;
import com.interviewcoach.position.application.service.PositionAnalysisStateService.PositionSubmission;
import com.interviewcoach.position.application.service.PositionAnalysisStateService.SubmittedTask;
import com.interviewcoach.position.infrastructure.config.PositionAnalysisProperties;
import com.interviewcoach.position.infrastructure.redis.PositionPublicSubmissionLock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 在一个短事务中登记岗位和 WAITING 任务，并在提交后触发可重建的 Redis 队列投影。
 * MySQL 是任务状态的最终事实，Redis 仅用于公平调度，投影失败不会回滚已提交的岗位。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PositionSubmissionService {

    private final PositionAnalysisStateService stateService;
    private final PositionAnalysisProperties properties;
    private final PositionPublicSubmissionLock publicSubmissionLock;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * 个人创建在当前事务登记岗位和任务；这里只发布轻量事件，监听器在事务提交后才投影 Redis。
     */
    @Transactional
    public SubmittedTask submitPersonal(Long userId, PositionSubmission submission) {
        SubmittedTask submitted = stateService.createPersonalPosition(
                userId,
                submission,
                properties.getPersonalActiveLimit(),
                properties.getPersonalWaitingLimit(),
                properties.getSubmissionInterval());
        logRegistered("CREATE_PERSONAL", userId, submitted);
        publishAfterCommit(submitted);
        return submitted;
    }

    /**
     * 个人重解析与创建共用频控和事务后入队边界，本事务不执行 Redis 操作或远程解析。
     */
    @Transactional
    public SubmittedTask reparsePersonal(Long userId, Long positionId) {
        SubmittedTask submitted = stateService.reparsePersonalPosition(
                userId,
                positionId,
                properties.getPersonalWaitingLimit(),
                properties.getSubmissionInterval());
        logRegistered("REPARSE_PERSONAL", userId, submitted);
        publishAfterCommit(submitted);
        return submitted;
    }

    /**
     * 公共创建先持有固定 Redisson 锁，再完成等待上限检查和数据库事务。
     * stateService 返回时内部事务已经提交，随后发布事件会通过 fallbackExecution 立即交给异步监听器。
     */
    public SubmittedTask submitPublic(Long requestUserId, PositionSubmission submission) {
        SubmittedTask submitted = publicSubmissionLock.execute(() ->
                stateService.createPublicPosition(
                        requestUserId,
                        submission,
                        properties.getPublicWaitingLimit()));
        logRegistered("CREATE_PUBLIC", requestUserId, submitted);
        publishProjectionRequest(submitted);
        return submitted;
    }

    /**
     * 公共重试与创建共用同一提交锁，防止等待上限被并发穿透；事件发布时数据库已提交。
     */
    public SubmittedTask reparsePublic(Long requestUserId, Long positionId) {
        SubmittedTask submitted = publicSubmissionLock.execute(() ->
                stateService.reparsePublicPosition(
                        requestUserId,
                        positionId,
                        properties.getPublicWaitingLimit()));
        logRegistered("REPARSE_PUBLIC", requestUserId, submitted);
        publishProjectionRequest(submitted);
        return submitted;
    }

    /**
     * 个人路径在外围事务中发布事件，实际监听由 AFTER_COMMIT 阶段触发。
     */
    private void publishAfterCommit(SubmittedTask submitted) {
        publishProjectionRequest(submitted);
    }

    /**
     * 统一发布队列投影事件：个人路径注册事务事件，公共路径因事务已结束而走 fallbackExecution。
     * 两种路径都只向有界执行器提交轻量工作，不在调用线程等待 Redis 重试。
     */
    private void publishProjectionRequest(SubmittedTask submitted) {
        eventPublisher.publishEvent(new PositionAnalysisRequestedEvent(
                submitted.taskId(), submitted.positionId(), submitted.queueOwner()));
        log.debug("[PositionAnalysis] 已发布事务后队列投影事件: taskId={}, positionId={}, queueOwner={}",
                submitted.taskId(), submitted.positionId(), submitted.queueOwner());
    }

    private void logRegistered(String action, Long requestUserId, SubmittedTask submitted) {
        log.info("[PositionAnalysis] 数据库任务已登记: action={}, taskId={}, positionId={}, "
                        + "requestUserId={}, queueOwner={}, status={}",
                action,
                submitted.taskId(),
                submitted.positionId(),
                requestUserId,
                submitted.queueOwner(),
                submitted.status());
    }
}
