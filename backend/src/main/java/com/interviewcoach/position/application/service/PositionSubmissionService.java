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

    /** 在 MySQL 事务中创建或替换岗位当前任务，并执行个人/公共容量与频控规则。 */
    private final PositionAnalysisStateService stateService;
    /** 提供个人岗位数、等待数、提交间隔和公共等待数等已校验配置。 */
    private final PositionAnalysisProperties properties;
    /** 将公共创建和重解析的等待上限检查串行化；Redis 不可用时公共提交失败关闭。 */
    private final PositionPublicSubmissionLock publicSubmissionLock;
    /** 发布事务提交后的 Redis 队列投影事件，事件本身不是真实任务状态。 */
    private final ApplicationEventPublisher eventPublisher;

    /**
     * 个人创建在当前事务登记岗位和任务；这里只发布轻量事件，监听器在事务提交后才投影 Redis。
     */
    @Transactional
    public SubmittedTask submitPersonal(Long userId, PositionSubmission submission) {
        // 在当前事务锁用户并登记个人岗位与 WAITING 任务，成功后才更新个人提交时间。
        SubmittedTask submitted = stateService.createPersonalPosition(
                userId,
                submission,
                properties.getPersonalActiveLimit(),
                properties.getPersonalWaitingLimit(),
                properties.getSubmissionInterval());
        logRegistered("CREATE_PERSONAL", userId, submitted);
        // 在事务内发布事件，监听器只会在提交成功后把任务投影到 Redis。
        publishAfterCommit(submitted);
        return submitted;
    }

    /**
     * 个人重解析与创建共用频控和事务后入队边界，本事务不执行 Redis 操作或远程解析。
     */
    @Transactional
    public SubmittedTask reparsePersonal(Long userId, Long positionId) {
        // 替换旧终态任务并登记新 WAITING；旧正式画像仍保留为可用事实。
        SubmittedTask submitted = stateService.reparsePersonalPosition(
                userId,
                positionId,
                properties.getPersonalWaitingLimit(),
                properties.getSubmissionInterval());
        logRegistered("REPARSE_PERSONAL", userId, submitted);
        // 事务回滚时 AFTER_COMMIT 监听不会执行，因此失败提交不会产生 Redis 新成员。
        publishAfterCommit(submitted);
        return submitted;
    }

    /**
     * 公共创建先持有固定 Redisson 锁，再完成等待上限检查和数据库事务。
     * stateService 返回时内部事务已经提交，随后发布事件会通过 fallbackExecution 立即交给异步监听器。
     */
    public SubmittedTask submitPublic(Long requestUserId, PositionSubmission submission) {
        // 固定 PUBLIC 锁覆盖等待上限检查和内部事务提交，避免并发请求同时穿透上限。
        SubmittedTask submitted = publicSubmissionLock.execute(() ->
                stateService.createPublicPosition(
                        requestUserId,
                        submission,
                        properties.getPublicWaitingLimit()));
        logRegistered("CREATE_PUBLIC", requestUserId, submitted);
        // 内部事务已经返回，fallbackExecution 会让监听器把轻量工作交给有界执行器。
        publishProjectionRequest(submitted);
        return submitted;
    }

    /**
     * 公共重试与创建共用同一提交锁，防止等待上限被并发穿透；事件发布时数据库已提交。
     */
    public SubmittedTask reparsePublic(Long requestUserId, Long positionId) {
        // 与公共创建复用同一锁，终态任务删除和新 WAITING 插入在状态服务事务内完成。
        SubmittedTask submitted = publicSubmissionLock.execute(() ->
                stateService.reparsePublicPosition(
                        requestUserId,
                        positionId,
                        properties.getPublicWaitingLimit()));
        logRegistered("REPARSE_PUBLIC", requestUserId, submitted);
        // Redis 投影失败不会回滚已经提交的公共岗位任务，后续由告警和启动恢复处理。
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
        // 事件仅携带可重建投影需要的任务、岗位和参与者标识，不发送 JD 或候选画像。
        eventPublisher.publishEvent(new PositionAnalysisRequestedEvent(
                submitted.taskId(), submitted.positionId(), submitted.queueOwner()));
        log.debug("[PositionAnalysis] 已发布事务后队列投影事件: taskId={}, positionId={}, queueOwner={}",
                submitted.taskId(), submitted.positionId(), submitted.queueOwner());
    }

    /** 记录 MySQL 登记结果及稳定标识，不输出 JD 正文、候选画像或模型内容。 */
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
