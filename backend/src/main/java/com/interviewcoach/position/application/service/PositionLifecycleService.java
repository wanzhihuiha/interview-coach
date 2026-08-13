package com.interviewcoach.position.application.service;

import com.interviewcoach.common.exception.BusinessException;
import com.interviewcoach.interview.domain.entity.InterviewStatus;
import com.interviewcoach.interview.domain.repository.InterviewRepository;
import com.interviewcoach.position.application.event.PositionAnalysisWaitingRemovedEvent;
import com.interviewcoach.position.domain.entity.Position;
import com.interviewcoach.position.domain.entity.PositionAnalysisTask;
import com.interviewcoach.position.domain.entity.PositionAnalysisTaskStatus;
import com.interviewcoach.position.domain.repository.PositionAnalysisTaskRepository;
import com.interviewcoach.position.domain.repository.PositionProfileRepository;
import com.interviewcoach.position.domain.repository.PositionRepository;
import com.interviewcoach.user.domain.repository.UserRepository;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 维护个人/公共岗位的归档和永久删除边界，并在事务提交后同步 WAITING Redis 投影。
 */
@Service
@RequiredArgsConstructor
public class PositionLifecycleService {

    /** 锁定个人岗位所有者，确保归档释放数量名额与个人创建的容量检查使用同一串行边界。 */
    private final UserRepository userRepository;
    /** 按个人归属或公共类型锁定并删除岗位实体。 */
    private final PositionRepository positionRepository;
    /** 锁定、删除当前任务，并据 WAITING 状态触发 Redis 投影清理。 */
    private final PositionAnalysisTaskRepository taskRepository;
    /** 在永久删除岗位前删除已确认的正式画像；归档不会删除画像。 */
    private final PositionProfileRepository profileRepository;
    /** 检查是否仍有使用该岗位的进行中面试，历史已结束面试不会被删除。 */
    private final InterviewRepository interviewRepository;
    /** 发布事务后 WAITING 投影移除事件，数据库任务事实不依赖事件成功。 */
    private final ApplicationEventPublisher eventPublisher;

    /**
     * 个人归档先锁用户行再锁岗位，和创建的数量检查保持同一顺序；重复归档直接成功。
     */
    @Transactional
    public void archivePersonal(Long userId, Long positionId) {
        // 先锁所有者再锁个人岗位，和个人创建事务保持一致顺序并立即释放活动岗位名额。
        lockUser(userId);
        Position position = positionRepository.findOwnedPersonalByIdForUpdate(positionId, userId)
                .orElseThrow(() -> notFound());
        // 写入归档时间并按任务状态决定删除任务或等待运行中的 Worker 收口。
        archiveLocked(position);
    }

    /**
     * 管理员只能归档公共岗位；入口角色由 Controller 保证，查询再次约束资源类型。
     */
    @Transactional
    public void archivePublic(Long positionId) {
        // 公共查询条件是管理入口后的第二层资源类型约束，个人岗位在此按不存在处理。
        Position position = positionRepository.findPublicByIdForUpdate(positionId)
                .orElseThrow(() -> notFound());
        // 公共岗位归档后普通用户立即不可见，正式画像仍保留供历史面试快照追溯。
        archiveLocked(position);
    }

    /**
     * 个人永久删除只允许已归档、无 RUNNING 任务且无进行中面试的本人岗位。
     */
    @Transactional
    public void deletePersonal(Long userId, Long positionId) {
        // 锁查询同时执行个人资源归属校验，越权和不存在统一返回岗位不存在。
        Position position = positionRepository.findOwnedPersonalByIdForUpdate(positionId, userId)
                .orElseThrow(() -> notFound());
        // 只有归档、无 RUNNING 且无进行中面试时才按依赖顺序永久删除。
        deleteLocked(position);
    }

    /**
     * 管理员永久删除同样只作用于公共岗位，并复用统一状态守卫。
     */
    @Transactional
    public void deletePublic(Long positionId) {
        // 管理员角色由 Controller 保障，本查询再次拒绝个人岗位进入公共删除流程。
        Position position = positionRepository.findPublicByIdForUpdate(positionId)
                .orElseThrow(() -> notFound());
        // 永久删除不清理历史面试，因为历史流程读取创建时保存的岗位快照。
        deleteLocked(position);
    }

    /**
     * 归档立即删除非运行任务；RUNNING 必须保留到 Worker 的远程调用真正结束。
     */
    private void archiveLocked(Position position) {
        if (position.isArchived()) {
            return;
        }
        // 按 Position -> Task 固定锁序读取当前任务，归档判断基于 MySQL 状态而非 Redis 投影。
        PositionAnalysisTask task = taskRepository
                .findByPositionIdForUpdate(position.getId())
                .orElse(null);

        // 先持久化归档事实；个人名额和普通公共可见性从该字段立即变化。
        position.setArchivedAt(LocalDateTime.now());
        positionRepository.save(position);
        if (task == null || task.getStatus() == PositionAnalysisTaskStatus.RUNNING) {
            return;
        }
        if (task.getStatus() == PositionAnalysisTaskStatus.WAITING) {
            // WAITING 任务将从 MySQL 删除，提交后异步移除可重建的 Redis 队列成员。
            publishWaitingRemoval(task);
        }
        taskRepository.delete(task);
    }

    /**
     * 永久删除按 Position -> Task -> Profile 顺序收口，不删除任何历史 Interview 记录。
     */
    private void deleteLocked(Position position) {
        if (!position.isArchived()) {
            throw new BusinessException(
                    PositionErrorCode.POSITION_NOT_ARCHIVED,
                    "岗位归档后才能永久删除");
        }
        // 锁定当前任务后拒绝删除仍由 Worker 使用的 RUNNING 任务，避免释放其状态归属。
        PositionAnalysisTask task = taskRepository
                .findByPositionIdForUpdate(position.getId())
                .orElse(null);
        if (task != null && task.getStatus() == PositionAnalysisTaskStatus.RUNNING) {
            throw new BusinessException(
                    PositionErrorCode.POSITION_ANALYSIS_RUNNING,
                    "岗位解析仍在运行，暂不能永久删除");
        }
        // 进行中面试仍依赖这条岗位记录的生命周期锁定，存在时禁止不可恢复删除。
        if (interviewRepository.existsByPositionIdAndStatus(
                position.getId(), InterviewStatus.IN_PROGRESS)) {
            throw new BusinessException(
                    PositionErrorCode.POSITION_HAS_ACTIVE_INTERVIEW,
                    "岗位仍有关联的进行中面试，暂不能永久删除");
        }

        if (task != null) {
            if (task.getStatus() == PositionAnalysisTaskStatus.WAITING) {
                // 数据库删除后异步清除 WAITING 投影；残留投影也会被领取时的数据库校验淘汰。
                publishWaitingRemoval(task);
            }
            taskRepository.delete(task);
        }
        // 依次删除正式画像和岗位；已结束面试仅保留自己的快照，不在这里级联删除。
        profileRepository.findByPositionIdForUpdate(position.getId())
                .ifPresent(profileRepository::delete);
        positionRepository.delete(position);
    }

    /** 发布轻量事务事件；监听器只清理 Redis 投影，失败不会恢复已删除的 MySQL 任务。 */
    private void publishWaitingRemoval(PositionAnalysisTask task) {
        eventPublisher.publishEvent(new PositionAnalysisWaitingRemovedEvent(
                task.getId(), task.getQueueOwner()));
    }

    /** 悲观锁读取个人岗位所有者；用户缺失时终止归档事务，不泄露其他资源信息。 */
    private void lockUser(Long userId) {
        userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new BusinessException(
                        PositionErrorCode.POSITION_ACCESS_DENIED,
                        "用户不存在或不可用"));
    }

    /** 统一生成岗位不存在错误，使不存在、资源类型不符和非本人三类结果保持同一公开口径。 */
    private BusinessException notFound() {
        return new BusinessException(PositionErrorCode.POSITION_NOT_FOUND, "岗位不存在");
    }
}
