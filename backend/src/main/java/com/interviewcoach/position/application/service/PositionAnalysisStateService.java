package com.interviewcoach.position.application.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.interviewcoach.common.exception.BusinessException;
import com.interviewcoach.position.domain.entity.Position;
import com.interviewcoach.position.domain.entity.PositionAnalysisTask;
import com.interviewcoach.position.domain.entity.PositionAnalysisTaskStatus;
import com.interviewcoach.position.domain.entity.PositionAuditStatus;
import com.interviewcoach.position.domain.entity.PositionParseStatus;
import com.interviewcoach.position.domain.entity.PositionProfile;
import com.interviewcoach.position.domain.model.PositionAnalysisQueueOwner;
import com.interviewcoach.position.domain.model.PositionProfileData;
import com.interviewcoach.position.domain.repository.PositionAnalysisTaskRepository;
import com.interviewcoach.position.domain.repository.PositionProfileRepository;
import com.interviewcoach.position.domain.repository.PositionRepository;
import com.interviewcoach.user.domain.entity.User;
import com.interviewcoach.user.domain.repository.UserRepository;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 用独立短事务维护岗位当前任务、候选画像和正式画像之间的状态边界。
 * 调用方负责输入提取、Redis 排队和事务外模型调用，本服务不执行远程 I/O。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PositionAnalysisStateService {

    private static final int MAX_ERROR_CODE_LENGTH = 50;
    private static final int MAX_ERROR_MESSAGE_LENGTH = 500;

    private final UserRepository userRepository;
    private final PositionRepository positionRepository;
    private final PositionAnalysisTaskRepository taskRepository;
    private final PositionProfileRepository profileRepository;
    private final ObjectMapper objectMapper;

    /**
     * 在只读短事务中固定本人个人岗位的任务状态，Redis 等待量由事务外调用方补充。
     */
    @Transactional(readOnly = true)
    public AnalysisStatusSnapshot loadPersonalAnalysisStatus(Long userId, Long positionId) {
        Position position = positionRepository
                .findByIdAndUserIdAndIsPublicFalse(positionId, userId)
                .orElseThrow(() -> new BusinessException(
                        PositionErrorCode.POSITION_NOT_FOUND, "岗位不存在"));
        return analysisStatusSnapshot(position);
    }

    /**
     * 在只读短事务中固定公共岗位的任务状态；管理员角色仍由 HTTP 入口校验。
     */
    @Transactional(readOnly = true)
    public AnalysisStatusSnapshot loadPublicAnalysisStatus(Long positionId) {
        Position position = positionRepository.findByIdAndIsPublicTrue(positionId)
                .orElseThrow(() -> new BusinessException(
                        PositionErrorCode.POSITION_NOT_FOUND, "岗位不存在"));
        return analysisStatusSnapshot(position);
    }

    /**
     * 用户行是本事务的第一条数据库读取，用它串行化岗位数、等待数和提交间隔。
     */
    @Transactional
    public SubmittedTask createPersonalPosition(
            Long userId,
            PositionSubmission submission,
            int maxActivePositions,
            int maxWaitingTasks,
            Duration minimumSubmissionInterval) {
        validatePositiveLimit(maxActivePositions, "个人岗位上限");
        validatePositiveLimit(maxWaitingTasks, "个人等待任务上限");
        validateInterval(minimumSubmissionInterval);
        requireSubmission(submission);

        User user = lockUser(userId);
        String queueOwner = PositionAnalysisQueueOwner.forUser(userId);
        LocalDateTime now = LocalDateTime.now();
        validateActivePositionCapacity(userId, maxActivePositions);
        validateWaitingCapacity(queueOwner, maxWaitingTasks);
        validateSubmissionInterval(user, minimumSubmissionInterval, now);

        Position position = positionRepository.save(newPosition(userId, false, submission));
        PositionAnalysisTask task = taskRepository.save(
                newWaitingTask(position.getId(), userId, queueOwner));
        user.setLastPositionAnalysisSubmittedAt(now);
        userRepository.save(user);
        return submitted(position, task);
    }

    /**
     * 创建公共岗位。调用方必须先持有全局 PUBLIC 提交锁，确保等待上限检查不可并发穿透。
     */
    @Transactional
    public SubmittedTask createPublicPosition(
            Long requestUserId,
            PositionSubmission submission,
            int maxWaitingTasks) {
        requireRequestUser(requestUserId);
        validatePositiveLimit(maxWaitingTasks, "公共等待任务上限");
        requireSubmission(submission);
        validateWaitingCapacity(PositionAnalysisQueueOwner.PUBLIC, maxWaitingTasks);

        Position position = positionRepository.save(newPosition(null, true, submission));
        PositionAnalysisTask task = taskRepository.save(newWaitingTask(
                position.getId(), requestUserId, PositionAnalysisQueueOwner.PUBLIC));
        return submitted(position, task);
    }

    /**
     * 个人重新解析继续锁用户行并共用等待数和提交间隔，但不重复检查岗位数量上限。
     */
    @Transactional
    public SubmittedTask reparsePersonalPosition(
            Long userId,
            Long positionId,
            int maxWaitingTasks,
            Duration minimumSubmissionInterval) {
        validatePositiveLimit(maxWaitingTasks, "个人等待任务上限");
        validateInterval(minimumSubmissionInterval);

        User user = lockUser(userId);
        Position position = positionRepository.findOwnedPersonalByIdForUpdate(positionId, userId)
                .orElseThrow(() -> new BusinessException(
                        PositionErrorCode.POSITION_NOT_FOUND, "岗位不存在"));
        requireActive(position);

        PositionAnalysisTask currentTask = taskRepository
                .findByPositionIdForUpdate(positionId)
                .orElse(null);
        requireReplaceable(currentTask);

        String queueOwner = PositionAnalysisQueueOwner.forUser(userId);
        LocalDateTime now = LocalDateTime.now();
        validateWaitingCapacity(queueOwner, maxWaitingTasks);
        validateSubmissionInterval(user, minimumSubmissionInterval, now);
        deleteTerminalTaskBeforeReplacement(currentTask);

        PositionAnalysisTask task = taskRepository.save(
                newWaitingTask(positionId, userId, queueOwner));
        user.setLastPositionAnalysisSubmittedAt(now);
        userRepository.save(user);
        return submitted(position, task);
    }

    /**
     * 重新解析公共岗位。调用方必须先持有全局 PUBLIC 提交锁并完成管理员入口鉴权。
     */
    @Transactional
    public SubmittedTask reparsePublicPosition(
            Long requestUserId,
            Long positionId,
            int maxWaitingTasks) {
        requireRequestUser(requestUserId);
        validatePositiveLimit(maxWaitingTasks, "公共等待任务上限");

        Position position = positionRepository.findPublicByIdForUpdate(positionId)
                .orElseThrow(() -> new BusinessException(
                        PositionErrorCode.POSITION_NOT_FOUND, "岗位不存在"));
        requireActive(position);
        PositionAnalysisTask currentTask = taskRepository
                .findByPositionIdForUpdate(positionId)
                .orElse(null);
        requireReplaceable(currentTask);
        validateWaitingCapacity(PositionAnalysisQueueOwner.PUBLIC, maxWaitingTasks);
        deleteTerminalTaskBeforeReplacement(currentTask);

        PositionAnalysisTask task = taskRepository.save(newWaitingTask(
                positionId, requestUserId, PositionAnalysisQueueOwner.PUBLIC));
        return submitted(position, task);
    }

    /**
     * 在调度器已经为队首任务取得本地容量后，按 Position -> Task 顺序原子领取任务。
     */
    @Transactional
    public AnalysisInput start(Long taskId) {
        PositionAnalysisTask snapshot = taskRepository.findById(taskId).orElse(null);
        if (snapshot == null) {
            return null;
        }
        Position position = positionRepository.findByIdForUpdate(snapshot.getPositionId()).orElse(null);
        if (position == null) {
            deleteOrphanTask(snapshot);
            log.error("[PositionAnalysis] 当前任务引用的岗位不存在: taskId={}, positionId={}",
                    taskId, snapshot.getPositionId());
            return null;
        }
        PositionAnalysisTask current = taskRepository.findByPositionId(snapshot.getPositionId()).orElse(null);
        if (current == null || !Objects.equals(current.getId(), taskId)
                || current.getStatus() != PositionAnalysisTaskStatus.WAITING) {
            return null;
        }
        if (position.isArchived()) {
            taskRepository.delete(current);
            return null;
        }
        String expectedOwner;
        try {
            expectedOwner = queueOwnerOf(position);
        } catch (IllegalArgumentException e) {
            failWaitingTaskForInvalidOwner(taskId, position.getId());
            log.error("[PositionAnalysis] 个人岗位缺少有效所有者: taskId={}, positionId={}",
                    taskId, position.getId());
            return null;
        }
        if (!Objects.equals(expectedOwner, current.getQueueOwner())) {
            failWaitingTaskForInvalidOwner(taskId, position.getId());
            log.error("[PositionAnalysis] 任务排队归属不一致: taskId={}, positionId={}",
                    taskId, position.getId());
            return null;
        }

        LocalDateTime now = LocalDateTime.now();
        int updated = taskRepository.markRunning(
                taskId,
                position.getId(),
                PositionAnalysisTaskStatus.WAITING,
                PositionAnalysisTaskStatus.RUNNING,
                now,
                now);
        if (updated != 1) {
            return null;
        }
        return new AnalysisInput(
                taskId,
                position.getId(),
                current.getRequestUserId(),
                current.getQueueOwner(),
                position.getJdContent(),
                position.getJobCategory());
    }

    /**
     * 仅将当前 RUNNING 任务写为成功；岗位已归档时丢弃候选并删除任务。
     */
    @Transactional
    public CompletionOutcome complete(Long taskId, PositionProfileData candidateProfile) {
        String candidateJson = toJson(candidateProfile);
        LockedTask locked = lockCurrentTask(taskId);
        if (locked == null) {
            return CompletionOutcome.STALE;
        }
        if (locked.position().isArchived()) {
            taskRepository.delete(locked.task());
            return CompletionOutcome.DISCARDED_ARCHIVED;
        }

        LocalDateTime now = LocalDateTime.now();
        int updated = taskRepository.markSucceeded(
                taskId,
                locked.position().getId(),
                PositionAnalysisTaskStatus.RUNNING,
                PositionAnalysisTaskStatus.SUCCEEDED,
                candidateJson,
                now,
                now);
        return updated == 1 ? CompletionOutcome.APPLIED : CompletionOutcome.STALE;
    }

    /**
     * 仅将当前 RUNNING 任务写为失败；岗位已归档时在远程调用结束后直接删除任务。
     */
    @Transactional
    public CompletionOutcome fail(Long taskId, String errorCode, String errorMessage) {
        LockedTask locked = lockCurrentTask(taskId);
        if (locked == null) {
            return CompletionOutcome.STALE;
        }
        if (locked.position().isArchived()) {
            taskRepository.delete(locked.task());
            return CompletionOutcome.DISCARDED_ARCHIVED;
        }

        LocalDateTime now = LocalDateTime.now();
        int updated = taskRepository.markFailed(
                taskId,
                locked.position().getId(),
                PositionAnalysisTaskStatus.RUNNING,
                PositionAnalysisTaskStatus.FAILED,
                truncate(errorCode, MAX_ERROR_CODE_LENGTH, "ANALYSIS_FAILED"),
                truncate(errorMessage, MAX_ERROR_MESSAGE_LENGTH, "岗位解析失败"),
                now,
                now);
        return updated == 1 ? CompletionOutcome.APPLIED : CompletionOutcome.STALE;
    }

    /**
     * 个人岗位所有者用当前 taskId 确认候选；正式画像写入后删除当前任务。
     */
    @Transactional
    public void confirmPersonalCandidate(
            Long userId,
            Long positionId,
            Long taskId,
            PositionProfileData confirmedProfile) {
        Position position = positionRepository.findOwnedPersonalByIdForUpdate(positionId, userId)
                .orElseThrow(() -> new BusinessException(
                        PositionErrorCode.POSITION_NOT_FOUND, "岗位不存在"));
        requireActive(position);
        confirmLockedCandidate(position, taskId, userId, confirmedProfile);
    }

    /**
     * 管理员确认公共岗位候选；Controller 负责角色鉴权，本事务再次限制目标必须为公共岗位。
     */
    @Transactional
    public void confirmPublicCandidate(
            Long positionId,
            Long taskId,
            PositionProfileData confirmedProfile) {
        Position position = positionRepository.findPublicByIdForUpdate(positionId)
                .orElseThrow(() -> new BusinessException(
                        PositionErrorCode.POSITION_NOT_FOUND, "岗位不存在"));
        requireActive(position);
        confirmLockedCandidate(position, taskId, null, confirmedProfile);
    }

    private User lockUser(Long userId) {
        requireRequestUser(userId);
        return userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new BusinessException(
                        PositionErrorCode.POSITION_ACCESS_DENIED, "用户不存在或不可用"));
    }

    private AnalysisStatusSnapshot analysisStatusSnapshot(Position position) {
        PositionAnalysisTask task = taskRepository.findByPositionId(position.getId()).orElse(null);
        return new AnalysisStatusSnapshot(
                position.getId(),
                position.isArchived(),
                task == null ? null : task.getId(),
                task == null ? null : task.getStatus(),
                task == null ? null : task.getQueueOwner(),
                task == null ? null : task.getErrorCode(),
                task == null ? null : task.getErrorMessage(),
                profileRepository.existsByPositionId(position.getId()));
    }

    private void validateActivePositionCapacity(Long userId, int maxActivePositions) {
        if (positionRepository.countByUserIdAndIsPublicFalseAndArchivedAtIsNull(userId)
                >= maxActivePositions) {
            throw new BusinessException(
                    PositionErrorCode.POSITION_LIMIT_EXCEEDED,
                    "未归档个人岗位数量已达上限");
        }
    }

    private void validateWaitingCapacity(String queueOwner, int maxWaitingTasks) {
        if (taskRepository.countByQueueOwnerAndStatus(
                queueOwner, PositionAnalysisTaskStatus.WAITING) >= maxWaitingTasks) {
            throw new BusinessException(
                    PositionErrorCode.POSITION_WAITING_LIMIT_EXCEEDED,
                    "等待解析的岗位数量已达上限");
        }
    }

    private void validateSubmissionInterval(
            User user, Duration minimumSubmissionInterval, LocalDateTime now) {
        LocalDateTime lastSubmittedAt = user.getLastPositionAnalysisSubmittedAt();
        if (lastSubmittedAt == null) {
            return;
        }
        LocalDateTime nextAllowedAt = lastSubmittedAt.plus(minimumSubmissionInterval);
        if (!now.isBefore(nextAllowedAt)) {
            return;
        }
        long remainingSeconds = Math.max(
                1L, Duration.between(now, nextAllowedAt).plusMillis(999).toSeconds());
        throw new BusinessException(
                PositionErrorCode.POSITION_SUBMISSION_TOO_FREQUENT,
                "岗位解析提交过于频繁，请在 " + remainingSeconds + " 秒后重试");
    }

    private void requireReplaceable(PositionAnalysisTask currentTask) {
        if (currentTask == null) {
            return;
        }
        if (currentTask.getStatus() == PositionAnalysisTaskStatus.WAITING
                || currentTask.getStatus() == PositionAnalysisTaskStatus.RUNNING) {
            throw new BusinessException(
                    PositionErrorCode.POSITION_ANALYSIS_IN_PROGRESS,
                    "岗位正在等待或解析中，请稍后重试");
        }
    }

    /**
     * Hibernate 可能先执行 insert；删除终态任务后显式 flush，确保唯一位置先释放。
     */
    private void deleteTerminalTaskBeforeReplacement(PositionAnalysisTask currentTask) {
        if (currentTask == null) {
            return;
        }
        taskRepository.delete(currentTask);
        taskRepository.flush();
    }

    private LockedTask lockCurrentTask(Long taskId) {
        PositionAnalysisTask snapshot = taskRepository.findById(taskId).orElse(null);
        if (snapshot == null) {
            return null;
        }
        Position position = positionRepository.findByIdForUpdate(snapshot.getPositionId()).orElse(null);
        if (position == null) {
            deleteOrphanTask(snapshot);
            log.error("[PositionAnalysis] 当前任务引用的岗位不存在: taskId={}, positionId={}",
                    taskId, snapshot.getPositionId());
            return null;
        }
        PositionAnalysisTask current = taskRepository
                .findByPositionIdForUpdate(position.getId())
                .orElse(null);
        if (current == null || !Objects.equals(current.getId(), taskId)
                || current.getStatus() != PositionAnalysisTaskStatus.RUNNING) {
            return null;
        }
        return new LockedTask(position, current);
    }

    private void deleteOrphanTask(PositionAnalysisTask snapshot) {
        PositionAnalysisTask orphan = taskRepository
                .findByPositionIdForUpdate(snapshot.getPositionId())
                .orElse(null);
        if (orphan != null && Objects.equals(orphan.getId(), snapshot.getId())) {
            taskRepository.delete(orphan);
        }
    }

    private void failWaitingTaskForInvalidOwner(Long taskId, Long positionId) {
        LocalDateTime now = LocalDateTime.now();
        taskRepository.markFailed(
                taskId,
                positionId,
                PositionAnalysisTaskStatus.WAITING,
                PositionAnalysisTaskStatus.FAILED,
                "QUEUE_OWNER_INVALID",
                "任务排队归属无效",
                now,
                now);
    }

    private void confirmLockedCandidate(
            Position position,
            Long taskId,
            Long profileUserId,
            PositionProfileData confirmedProfile) {
        PositionAnalysisTask task = taskRepository
                .findByPositionIdForUpdate(position.getId())
                .orElseThrow(() -> new BusinessException(
                        PositionErrorCode.POSITION_ANALYSIS_TASK_STALE,
                        "待确认任务已不存在，请刷新后重试"));
        if (!Objects.equals(task.getId(), taskId)
                || task.getStatus() != PositionAnalysisTaskStatus.SUCCEEDED
                || task.getCandidateProfileData() == null
                || task.getCandidateProfileData().isBlank()) {
            throw new BusinessException(
                    PositionErrorCode.POSITION_ANALYSIS_TASK_STALE,
                    "待确认任务已变化，请刷新后重试");
        }

        PositionProfile profile = profileRepository.findByPositionIdForUpdate(position.getId())
                .orElseGet(() -> {
                    PositionProfile created = new PositionProfile();
                    created.setPositionId(position.getId());
                    return created;
                });
        profile.setUserId(profileUserId);
        profile.setProfileData(toJson(confirmedProfile));
        profileRepository.save(profile);
        applyConfirmedLevel(position, confirmedProfile);
        positionRepository.save(position);
        taskRepository.delete(task);
    }

    private Position newPosition(Long userId, boolean publicPosition, PositionSubmission submission) {
        Position position = new Position();
        position.setUserId(userId);
        position.setPositionName(requireText(
                submission.positionName(),
                PositionErrorCode.POSITION_NAME_EMPTY,
                "岗位名称不能为空"));
        position.setCompanyName(trimToNull(submission.companyName()));
        position.setLocation(trimToNull(submission.location()));
        position.setSalaryRange(trimToNull(submission.salaryRange()));
        position.setJobCategory(requireText(
                submission.jobCategory(),
                PositionErrorCode.PROFILE_DATA_INVALID,
                "岗位类别不能为空"));
        position.setLevel(trimToNull(submission.level()));
        position.setJdContent(requireText(
                submission.jdContent(),
                PositionErrorCode.JD_CONTENT_EMPTY,
                "JD 描述不能为空"));
        // 旧列只为结构兼容保留；新 API 的任务与发布状态不得再读取它们。
        position.setParseStatus(PositionParseStatus.PENDING);
        position.setAuditStatus(PositionAuditStatus.PENDING);
        position.setIsPublic(publicPosition);
        return position;
    }

    private PositionAnalysisTask newWaitingTask(
            Long positionId, Long requestUserId, String queueOwner) {
        PositionAnalysisTask task = new PositionAnalysisTask();
        task.setPositionId(positionId);
        task.setRequestUserId(requestUserId);
        task.setQueueOwner(queueOwner);
        task.setStatus(PositionAnalysisTaskStatus.WAITING);
        return task;
    }

    private SubmittedTask submitted(Position position, PositionAnalysisTask task) {
        return new SubmittedTask(
                position.getId(),
                position.getPositionName(),
                task.getId(),
                task.getQueueOwner(),
                task.getStatus());
    }

    private String queueOwnerOf(Position position) {
        if (Boolean.TRUE.equals(position.getIsPublic())) {
            return PositionAnalysisQueueOwner.PUBLIC;
        }
        return PositionAnalysisQueueOwner.forUser(position.getUserId());
    }

    private void requireActive(Position position) {
        if (position.isArchived()) {
            throw new BusinessException(PositionErrorCode.POSITION_ARCHIVED, "岗位已归档");
        }
    }

    private void applyConfirmedLevel(Position position, PositionProfileData profile) {
        if (profile != null && profile.getBasicInfo() != null) {
            String level = trimToNull(profile.getBasicInfo().getLevel());
            if (level != null) {
                position.setLevel(level);
            }
        }
    }

    private String toJson(PositionProfileData profile) {
        if (profile == null) {
            throw new BusinessException(
                    PositionErrorCode.PROFILE_DATA_INVALID, "画像数据无效");
        }
        try {
            return objectMapper.writeValueAsString(profile);
        } catch (JsonProcessingException e) {
            throw new BusinessException(
                    PositionErrorCode.PROFILE_DATA_INVALID, "画像数据序列化失败", e);
        }
    }

    private String requireText(String value, int errorCode, String message) {
        String normalized = trimToNull(value);
        if (normalized == null) {
            throw new BusinessException(errorCode, message);
        }
        return normalized;
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String truncate(String value, int maxLength, String fallback) {
        String normalized = trimToNull(value);
        if (normalized == null) {
            normalized = fallback;
        }
        return normalized.length() <= maxLength
                ? normalized
                : normalized.substring(0, maxLength);
    }

    private void requireRequestUser(Long requestUserId) {
        if (requestUserId == null || requestUserId <= 0) {
            throw new BusinessException(
                    PositionErrorCode.POSITION_ACCESS_DENIED, "请求用户无效");
        }
    }

    private void requireSubmission(PositionSubmission submission) {
        if (submission == null) {
            throw new BusinessException(
                    PositionErrorCode.JD_CONTENT_EMPTY, "岗位提交内容不能为空");
        }
    }

    private void validatePositiveLimit(int limit, String name) {
        if (limit <= 0) {
            throw new IllegalStateException(name + "必须大于 0");
        }
    }

    private void validateInterval(Duration interval) {
        if (interval == null || interval.isNegative()) {
            throw new IllegalStateException("岗位解析提交间隔配置无效");
        }
    }

    public record PositionSubmission(
            String positionName,
            String companyName,
            String location,
            String salaryRange,
            String jobCategory,
            String level,
            String jdContent) {
    }

    public record SubmittedTask(
            Long positionId,
            String positionName,
            Long taskId,
            String queueOwner,
            PositionAnalysisTaskStatus status) {
    }

    public record AnalysisInput(
            Long taskId,
            Long positionId,
            Long requestUserId,
            String queueOwner,
            String jdContent,
            String jobCategory) {
    }

    public record AnalysisStatusSnapshot(
            Long positionId,
            boolean archived,
            Long taskId,
            PositionAnalysisTaskStatus status,
            String queueOwner,
            String errorCode,
            String errorMessage,
            boolean profileUsable) {
    }

    public enum CompletionOutcome {
        APPLIED,
        STALE,
        DISCARDED_ARCHIVED
    }

    private record LockedTask(Position position, PositionAnalysisTask task) {
    }
}
