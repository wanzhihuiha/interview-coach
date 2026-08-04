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

    private final UserRepository userRepository;
    private final PositionRepository positionRepository;
    private final PositionAnalysisTaskRepository taskRepository;
    private final PositionProfileRepository profileRepository;
    private final InterviewRepository interviewRepository;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * 个人归档先锁用户行再锁岗位，和创建的数量检查保持同一顺序；重复归档直接成功。
     */
    @Transactional
    public void archivePersonal(Long userId, Long positionId) {
        lockUser(userId);
        Position position = positionRepository.findOwnedPersonalByIdForUpdate(positionId, userId)
                .orElseThrow(() -> notFound());
        archiveLocked(position);
    }

    /**
     * 管理员只能归档公共岗位；入口角色由 Controller 保证，查询再次约束资源类型。
     */
    @Transactional
    public void archivePublic(Long positionId) {
        Position position = positionRepository.findPublicByIdForUpdate(positionId)
                .orElseThrow(() -> notFound());
        archiveLocked(position);
    }

    /**
     * 个人永久删除只允许已归档、无 RUNNING 任务且无进行中面试的本人岗位。
     */
    @Transactional
    public void deletePersonal(Long userId, Long positionId) {
        Position position = positionRepository.findOwnedPersonalByIdForUpdate(positionId, userId)
                .orElseThrow(() -> notFound());
        deleteLocked(position);
    }

    /**
     * 管理员永久删除同样只作用于公共岗位，并复用统一状态守卫。
     */
    @Transactional
    public void deletePublic(Long positionId) {
        Position position = positionRepository.findPublicByIdForUpdate(positionId)
                .orElseThrow(() -> notFound());
        deleteLocked(position);
    }

    /**
     * 归档立即删除非运行任务；RUNNING 必须保留到 Worker 的远程调用真正结束。
     */
    private void archiveLocked(Position position) {
        if (position.isArchived()) {
            return;
        }
        PositionAnalysisTask task = taskRepository
                .findByPositionIdForUpdate(position.getId())
                .orElse(null);

        position.setArchivedAt(LocalDateTime.now());
        positionRepository.save(position);
        if (task == null || task.getStatus() == PositionAnalysisTaskStatus.RUNNING) {
            return;
        }
        if (task.getStatus() == PositionAnalysisTaskStatus.WAITING) {
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
        PositionAnalysisTask task = taskRepository
                .findByPositionIdForUpdate(position.getId())
                .orElse(null);
        if (task != null && task.getStatus() == PositionAnalysisTaskStatus.RUNNING) {
            throw new BusinessException(
                    PositionErrorCode.POSITION_ANALYSIS_RUNNING,
                    "岗位解析仍在运行，暂不能永久删除");
        }
        if (interviewRepository.existsByPositionIdAndStatus(
                position.getId(), InterviewStatus.IN_PROGRESS)) {
            throw new BusinessException(
                    PositionErrorCode.POSITION_HAS_ACTIVE_INTERVIEW,
                    "岗位仍有关联的进行中面试，暂不能永久删除");
        }

        if (task != null) {
            if (task.getStatus() == PositionAnalysisTaskStatus.WAITING) {
                publishWaitingRemoval(task);
            }
            taskRepository.delete(task);
        }
        profileRepository.findByPositionIdForUpdate(position.getId())
                .ifPresent(profileRepository::delete);
        positionRepository.delete(position);
    }

    private void publishWaitingRemoval(PositionAnalysisTask task) {
        eventPublisher.publishEvent(new PositionAnalysisWaitingRemovedEvent(
                task.getId(), task.getQueueOwner()));
    }

    private void lockUser(Long userId) {
        userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new BusinessException(
                        PositionErrorCode.POSITION_ACCESS_DENIED,
                        "用户不存在或不可用"));
    }

    private BusinessException notFound() {
        return new BusinessException(PositionErrorCode.POSITION_NOT_FOUND, "岗位不存在");
    }
}
