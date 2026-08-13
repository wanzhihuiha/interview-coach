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
 * 用 Spring 事务维护岗位、当前任务、候选画像和正式画像之间的 MySQL 状态边界。
 * 调用方负责输入提取、Redis 排队和事务外模型调用，本服务不执行远程 I/O。
 * {@link Transactional} 使用默认 REQUIRED 传播：从代理入口调用时开启事务，被已有事务调用时加入外层事务，不能视为每次都创建独立事务。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PositionAnalysisStateService {

    /** 失败分类持久化的当前最大 UTF-16 字符数；固定值 50 的精确依据缺失，调小会截断更多代码。 */
    private static final int MAX_ERROR_CODE_LENGTH = 50;
    /** 脱敏失败说明持久化的当前最大 UTF-16 字符数；固定值 500 的精确依据缺失，调大增加存储，调小减少诊断信息。 */
    private static final int MAX_ERROR_MESSAGE_LENGTH = 500;

    /** 锁定可信用户并维护个人岗位提交时间的用户仓储。 */
    private final UserRepository userRepository;
    /** 按资源归属锁定岗位、统计个人容量并保存生命周期字段的岗位仓储。 */
    private final PositionRepository positionRepository;
    /** 维护一岗位一行当前任务以及条件状态更新的任务仓储。 */
    private final PositionAnalysisTaskRepository taskRepository;
    /** 读取、锁定并写入已确认正式画像的画像仓储。 */
    private final PositionProfileRepository profileRepository;
    /** 将候选和确认画像序列化为数据库 JSON 文本的项目映射器。 */
    private final ObjectMapper objectMapper;

    /**
     * 在只读短事务中固定本人个人岗位的任务状态，Redis 等待量由事务外调用方补充。
     */
    @Transactional(readOnly = true)
    public AnalysisStatusSnapshot loadPersonalAnalysisStatus(Long userId, Long positionId) {
        // 查询同时限制岗位 ID、可信用户归属和非公共类型，未命中统一按不存在处理。
        Position position = positionRepository
                .findByIdAndUserIdAndIsPublicFalse(positionId, userId)
                .orElseThrow(() -> new BusinessException(
                        PositionErrorCode.POSITION_NOT_FOUND, "岗位不存在"));
        // 再读取该岗位当前任务和正式画像存在性，固定为供事务外 Redis 估算补充的 MySQL 快照。
        return analysisStatusSnapshot(position);
    }

    /**
     * 在只读短事务中固定公共岗位的任务状态；管理员角色仍由 HTTP 入口校验。
     */
    @Transactional(readOnly = true)
    public AnalysisStatusSnapshot loadPublicAnalysisStatus(Long positionId) {
        // 管理入口角色由上游保证，本服务仍以 is_public 条件阻止通过该路径读取个人岗位。
        Position position = positionRepository.findByIdAndIsPublicTrue(positionId)
                .orElseThrow(() -> new BusinessException(
                        PositionErrorCode.POSITION_NOT_FOUND, "岗位不存在"));
        // 返回公共岗位的 MySQL 任务和正式画像事实，不在本事务读取 Redis 投影。
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

        // 用户行是第一条数据库锁，串行同一用户的岗位数、等待数与提交间隔检查。
        User user = lockUser(userId);
        String queueOwner = PositionAnalysisQueueOwner.forUser(userId);
        LocalDateTime now = LocalDateTime.now();
        // 在持有用户锁时按 MySQL 当前事实检查个人岗位容量、WAITING 容量和成功提交间隔。
        validateActivePositionCapacity(userId, maxActivePositions);
        validateWaitingCapacity(queueOwner, maxWaitingTasks);
        validateSubmissionInterval(user, minimumSubmissionInterval, now);

        // 同一事务先写岗位，再以岗位 ID 写唯一当前 WAITING 任务，任一步失败会随事务回滚。
        Position position = positionRepository.save(newPosition(userId, false, submission));
        PositionAnalysisTask task = taskRepository.save(
                newWaitingTask(position.getId(), userId, queueOwner));
        // 只有岗位与任务登记进入事务后才更新时间；输入和前置检查失败不消耗频控。
        user.setLastPositionAnalysisSubmittedAt(now);
        userRepository.save(user);
        return submitted(position, task);
    }

    /**
     * 创建公共岗位。调用方必须先持有固定 PUBLIC 提交锁；只有共享同一 Redisson Key 空间的调用方才受该锁串行。
     * 跨实例部署是否共享该 Key 空间证据不足，MySQL 等待数检查本身不提供跨事务全局串行。
     */
    @Transactional
    public SubmittedTask createPublicPosition(
            Long requestUserId,
            PositionSubmission submission,
            int maxWaitingTasks) {
        requireRequestUser(requestUserId);
        validatePositiveLimit(maxWaitingTasks, "公共等待任务上限");
        requireSubmission(submission);
        // 调用方持有固定 PUBLIC 提交锁时，以 MySQL WAITING 数执行公共容量检查。
        validateWaitingCapacity(PositionAnalysisQueueOwner.PUBLIC, maxWaitingTasks);

        // 公共岗位没有资源所有者，requestUserId 仅写入任务作为发起人审计信息。
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

        // 继续锁用户行，使本次重新解析与同用户新建和其他重新解析共用频控串行边界。
        User user = lockUser(userId);
        // 按本人归属锁定活动候选岗位，其他用户和公共岗位统一按不存在处理。
        Position position = positionRepository.findOwnedPersonalByIdForUpdate(positionId, userId)
                .orElseThrow(() -> new BusinessException(
                        PositionErrorCode.POSITION_NOT_FOUND, "岗位不存在"));
        requireActive(position);

        // 在 Position 之后锁定唯一当前 Task，维持固定锁顺序并拒绝 WAITING/RUNNING 被替换。
        PositionAnalysisTask currentTask = taskRepository
                .findByPositionIdForUpdate(positionId)
                .orElse(null);
        requireReplaceable(currentTask);

        String queueOwner = PositionAnalysisQueueOwner.forUser(userId);
        LocalDateTime now = LocalDateTime.now();
        // 重新解析不增加岗位数，但仍检查个人 WAITING 容量和距上次成功提交的间隔。
        validateWaitingCapacity(queueOwner, maxWaitingTasks);
        validateSubmissionInterval(user, minimumSubmissionInterval, now);
        // 先删除并 flush 旧终态任务，释放一岗位一任务唯一位置，再插入新 WAITING。
        deleteTerminalTaskBeforeReplacement(currentTask);

        PositionAnalysisTask task = taskRepository.save(
                newWaitingTask(positionId, userId, queueOwner));
        user.setLastPositionAnalysisSubmittedAt(now);
        userRepository.save(user);
        return submitted(position, task);
    }

    /**
     * 重新解析公共岗位。调用方必须先持有固定 PUBLIC 提交锁并完成管理员入口鉴权；
     * 该锁仅串行共享同一 Redisson Key 空间的调用方，跨实例部署是否共享该 Key 空间证据不足。
     */
    @Transactional
    public SubmittedTask reparsePublicPosition(
            Long requestUserId,
            Long positionId,
            int maxWaitingTasks) {
        requireRequestUser(requestUserId);
        validatePositiveLimit(maxWaitingTasks, "公共等待任务上限");

        // 管理角色由入口保证，本事务仍按公共类型锁定岗位并拒绝个人资源。
        Position position = positionRepository.findPublicByIdForUpdate(positionId)
                .orElseThrow(() -> new BusinessException(
                        PositionErrorCode.POSITION_NOT_FOUND, "岗位不存在"));
        requireActive(position);
        // 在 Position 之后锁定当前任务，只有无任务或终态任务可以被新 WAITING 替换。
        PositionAnalysisTask currentTask = taskRepository
                .findByPositionIdForUpdate(positionId)
                .orElse(null);
        requireReplaceable(currentTask);
        // 共享同一 Redisson Key 空间的调用方先由固定 PUBLIC 锁串行，再以 MySQL WAITING 数检查容量；计数本身不提供跨事务全局串行。
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
        // 先按任务 ID 取非锁定快照，只用于定位所属岗位；不存在表示 Redis 投影已经过期。
        PositionAnalysisTask snapshot = taskRepository.findById(taskId).orElse(null);
        if (snapshot == null) {
            return null;
        }
        // 按固定 Position -> Task 顺序先锁岗位；孤儿任务在本事务内删除并返回未领取。
        Position position = positionRepository.findByIdForUpdate(snapshot.getPositionId()).orElse(null);
        if (position == null) {
            deleteOrphanTask(snapshot);
            log.error("[PositionAnalysis] 当前任务引用的岗位不存在: taskId={}, positionId={}",
                    taskId, snapshot.getPositionId());
            return null;
        }
        // 回读岗位唯一当前任务，拒绝已替换、非 WAITING 或 taskId 不一致的迟到 Redis 预留。
        PositionAnalysisTask current = taskRepository.findByPositionId(snapshot.getPositionId()).orElse(null);
        if (current == null || !Objects.equals(current.getId(), taskId)
                || current.getStatus() != PositionAnalysisTaskStatus.WAITING) {
            return null;
        }
        if (position.isArchived()) {
            // 归档岗位不再执行模型，直接删除仍 WAITING 的当前任务并让调度器清理 Redis 预留。
            taskRepository.delete(current);
            return null;
        }
        String expectedOwner;
        try {
            // 根据岗位公共属性和服务端归属字段重算预期 queueOwner，不信任 Redis 或请求传入值。
            expectedOwner = queueOwnerOf(position);
        } catch (IllegalArgumentException e) {
            failWaitingTaskForInvalidOwner(taskId, position.getId());
            log.error("[PositionAnalysis] 个人岗位缺少有效所有者: taskId={}, positionId={}",
                    taskId, position.getId());
            return null;
        }
        if (!Objects.equals(expectedOwner, current.getQueueOwner())) {
            // 数据库 queueOwner 与岗位事实不一致时条件写 FAILED，避免错误参与者继续领取。
            failWaitingTaskForInvalidOwner(taskId, position.getId());
            log.error("[PositionAnalysis] 任务排队归属不一致: taskId={}, positionId={}",
                    taskId, position.getId());
            return null;
        }

        LocalDateTime now = LocalDateTime.now();
        // 最终以 taskId、positionId 和 WAITING 源状态条件原子写 RUNNING；0 行表示并发状态已变化。
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
        // 只把数据库读取出的不可变输入交给事务外 Worker，后续不依赖延迟加载实体。
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
        // 先确保候选可序列化；失败时不锁任务，也不会把无效候选写成成功。
        String candidateJson = toJson(candidateProfile);
        // 按 Position -> 当前 RUNNING Task 锁顺序确认仍是本次任务，迟到 Worker 返回 STALE。
        LockedTask locked = lockCurrentTask(taskId);
        if (locked == null) {
            return CompletionOutcome.STALE;
        }
        if (locked.position().isArchived()) {
            // 归档发生在模型运行期间时删除任务并丢弃候选，正式画像保持不变。
            taskRepository.delete(locked.task());
            return CompletionOutcome.DISCARDED_ARCHIVED;
        }

        LocalDateTime now = LocalDateTime.now();
        // 以 RUNNING 源状态条件写候选和成功终态，防止重复或迟到完成覆盖当前任务。
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
        // 按 Position -> 当前 RUNNING Task 锁顺序确认失败仍属于本次任务。
        LockedTask locked = lockCurrentTask(taskId);
        if (locked == null) {
            return CompletionOutcome.STALE;
        }
        if (locked.position().isArchived()) {
            // 归档岗位的远程调用已经真实结束，删除任务而不保留失败或覆盖正式画像。
            taskRepository.delete(locked.task());
            return CompletionOutcome.DISCARDED_ARCHIVED;
        }

        LocalDateTime now = LocalDateTime.now();
        // 按持久化上限截断稳定分类和脱敏说明，再以 RUNNING 源状态条件写失败终态。
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
        // 最终确认按可信用户归属锁定个人岗位，公共或他人岗位统一按不存在处理。
        Position position = positionRepository.findOwnedPersonalByIdForUpdate(positionId, userId)
                .orElseThrow(() -> new BusinessException(
                        PositionErrorCode.POSITION_NOT_FOUND, "岗位不存在"));
        requireActive(position);
        // 在同一事务锁定当前成功任务，写正式画像、同步职级并删除候选任务。
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
        // 管理角色由入口保证，本事务仍用公共类型条件锁定资源，不能确认个人岗位。
        Position position = positionRepository.findPublicByIdForUpdate(positionId)
                .orElseThrow(() -> new BusinessException(
                        PositionErrorCode.POSITION_NOT_FOUND, "岗位不存在"));
        requireActive(position);
        // 公共正式画像的 userId 保持为空；确认成功后立即由公开查询识别为可发布。
        confirmLockedCandidate(position, taskId, null, confirmedProfile);
    }

    /** 按可信用户 ID 取得悲观写锁；不存在或非法 ID 统一拒绝后续个人岗位写入。 */
    private User lockUser(Long userId) {
        requireRequestUser(userId);
        return userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new BusinessException(
                        PositionErrorCode.POSITION_ACCESS_DENIED, "用户不存在或不可用"));
    }

    /** 组合岗位归档、唯一当前任务和正式画像存在性，形成不含 Redis 数据的状态快照。 */
    private AnalysisStatusSnapshot analysisStatusSnapshot(Position position) {
        // 当前任务不存在时各任务字段为空，正式画像存在性独立查询，二者不能互相推导。
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

    /** 以 MySQL 中本人未归档个人岗位数执行提交容量限制；公共和归档岗位不计入。 */
    private void validateActivePositionCapacity(Long userId, int maxActivePositions) {
        if (positionRepository.countByUserIdAndIsPublicFalseAndArchivedAtIsNull(userId)
                >= maxActivePositions) {
            throw new BusinessException(
                    PositionErrorCode.POSITION_LIMIT_EXCEEDED,
                    "未归档个人岗位数量已达上限");
        }
    }

    /** 以 MySQL 中指定参与者的 WAITING 当前任务数执行上限，不以 Redis 投影计数作为事实。 */
    private void validateWaitingCapacity(String queueOwner, int maxWaitingTasks) {
        if (taskRepository.countByQueueOwnerAndStatus(
                queueOwner, PositionAnalysisTaskStatus.WAITING) >= maxWaitingTasks) {
            throw new BusinessException(
                    PositionErrorCode.POSITION_WAITING_LIMIT_EXCEEDED,
                    "等待解析的岗位数量已达上限");
        }
    }

    /**
     * 根据用户上次成功登记时间计算下一次允许时间；不足整秒的剩余时间向上取整且至少返回 1 秒。
     * 其中 999 毫秒用于当前向上取整实现，精确呈现方式的产品依据缺失。
     */
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

    /** 只允许无任务、SUCCEEDED 或 FAILED 被重新解析替换；WAITING/RUNNING 必须继续等待当前任务。 */
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

    /**
     * 先用任务快照定位岗位，再按 Position -> 当前 Task 顺序锁定并确认 taskId 与 RUNNING 源状态。
     *
     * @return 当前任务仍可由本 Worker 收口时返回锁定对，否则返回空
     */
    private LockedTask lockCurrentTask(Long taskId) {
        // 初次快照不作为状态判断，只用于找到必须先锁定的岗位 ID。
        PositionAnalysisTask snapshot = taskRepository.findById(taskId).orElse(null);
        if (snapshot == null) {
            return null;
        }
        // 岗位不存在时按同一 positionId 再锁任务并仅删除仍与快照一致的孤儿记录。
        Position position = positionRepository.findByIdForUpdate(snapshot.getPositionId()).orElse(null);
        if (position == null) {
            deleteOrphanTask(snapshot);
            log.error("[PositionAnalysis] 当前任务引用的岗位不存在: taskId={}, positionId={}",
                    taskId, snapshot.getPositionId());
            return null;
        }
        // 岗位锁后再锁唯一当前任务，拒绝已经被替换或不再 RUNNING 的迟到结果。
        PositionAnalysisTask current = taskRepository
                .findByPositionIdForUpdate(position.getId())
                .orElse(null);
        if (current == null || !Objects.equals(current.getId(), taskId)
                || current.getStatus() != PositionAnalysisTaskStatus.RUNNING) {
            return null;
        }
        return new LockedTask(position, current);
    }

    /** 仅删除 positionId 下仍与非锁定快照 taskId 一致的孤儿任务，避免误删并发替代任务。 */
    private void deleteOrphanTask(PositionAnalysisTask snapshot) {
        PositionAnalysisTask orphan = taskRepository
                .findByPositionIdForUpdate(snapshot.getPositionId())
                .orElse(null);
        if (orphan != null && Objects.equals(orphan.getId(), snapshot.getId())) {
            taskRepository.delete(orphan);
        }
    }

    /** 以 WAITING 源状态条件把无效队列归属任务收口为稳定失败；并发变化时不覆盖新状态。 */
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

    /**
     * 在已锁定活动岗位的事务中确认精确当前成功候选，写入或替换正式画像、同步非空职级并删除任务。
     * 请求中的确认画像会被序列化为正式版本；当前候选 JSON 只用于证明该 taskId 具备可确认结果。
     */
    private void confirmLockedCandidate(
            Position position,
            Long taskId,
            Long profileUserId,
            PositionProfileData confirmedProfile) {
        // 在 Position 锁之后锁定岗位唯一当前任务，并要求请求 taskId、SUCCEEDED 和候选 JSON 同时有效。
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

        // 锁定现有正式画像；首次确认没有记录时在同一事务创建一岗位一份正式画像。
        PositionProfile profile = profileRepository.findByPositionIdForUpdate(position.getId())
                .orElseGet(() -> {
                    PositionProfile created = new PositionProfile();
                    created.setPositionId(position.getId());
                    return created;
                });
        // 个人画像记录本人 ID，公共画像为空；序列化失败会使整个确认事务回滚。
        profile.setUserId(profileUserId);
        profile.setProfileData(toJson(confirmedProfile));
        profileRepository.save(profile);
        // 候选 basicInfo 中的非空职级同步回岗位；随后删除当前任务，不保留已确认任务历史。
        applyConfirmedLevel(position, confirmedProfile);
        positionRepository.save(position);
        taskRepository.delete(task);
    }

    /** 根据已规范化提交构造尚未持久化的个人或公共岗位，并填充仅供 V1 结构兼容的遗留状态列。 */
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

    /** 构造岗位唯一当前 WAITING 任务；requestUserId 仅供发起审计，queueOwner 由服务端生成。 */
    private PositionAnalysisTask newWaitingTask(
            Long positionId, Long requestUserId, String queueOwner) {
        PositionAnalysisTask task = new PositionAnalysisTask();
        task.setPositionId(positionId);
        task.setRequestUserId(requestUserId);
        task.setQueueOwner(queueOwner);
        task.setStatus(PositionAnalysisTaskStatus.WAITING);
        return task;
    }

    /** 把已持久化岗位和任务转换为提交服务及 HTTP 响应使用的不可变结果。 */
    private SubmittedTask submitted(Position position, PositionAnalysisTask task) {
        return new SubmittedTask(
                position.getId(),
                position.getPositionName(),
                task.getId(),
                task.getQueueOwner(),
                task.getStatus());
    }

    /** 根据数据库岗位类型和所属用户重建预期队列参与者，公共岗位固定 PUBLIC。 */
    private String queueOwnerOf(Position position) {
        if (Boolean.TRUE.equals(position.getIsPublic())) {
            return PositionAnalysisQueueOwner.PUBLIC;
        }
        return PositionAnalysisQueueOwner.forUser(position.getUserId());
    }

    /** 拒绝对已归档岗位执行确认或重新解析，不提供归档恢复路径。 */
    private void requireActive(Position position) {
        if (position.isArchived()) {
            throw new BusinessException(PositionErrorCode.POSITION_ARCHIVED, "岗位已归档");
        }
    }

    /** 只在确认画像包含非空 basicInfo.level 时更新岗位职级，其他情况下保留现有值。 */
    private void applyConfirmedLevel(Position position, PositionProfileData profile) {
        if (profile != null && profile.getBasicInfo() != null) {
            String level = trimToNull(profile.getBasicInfo().getLevel());
            if (level != null) {
                position.setLevel(level);
            }
        }
    }

    /** 将非空岗位画像序列化为数据库 JSON 文本，失败统一映射为画像数据错误并回滚调用事务。 */
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

    /** 去除首尾空白并按调用方指定错误码拒绝空值。 */
    private String requireText(String value, int errorCode, String message) {
        String normalized = trimToNull(value);
        if (normalized == null) {
            throw new BusinessException(errorCode, message);
        }
        return normalized;
    }

    /** 将空值或去除首尾空白后的空字符串统一转换为空值。 */
    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** 把空错误文本替换为安全兜底，并按 UTF-16 字符长度截断以符合当前持久化边界。 */
    private String truncate(String value, int maxLength, String fallback) {
        String normalized = trimToNull(value);
        if (normalized == null) {
            normalized = fallback;
        }
        return normalized.length() <= maxLength
                ? normalized
                : normalized.substring(0, maxLength);
    }

    /** 拒绝空或非正数的可信发起用户 ID；该字段不替代资源归属校验。 */
    private void requireRequestUser(Long requestUserId) {
        if (requestUserId == null || requestUserId <= 0) {
            throw new BusinessException(
                    PositionErrorCode.POSITION_ACCESS_DENIED, "请求用户无效");
        }
    }

    /** 拒绝缺失的岗位提交对象，字段内容由构造岗位时继续校验。 */
    private void requireSubmission(PositionSubmission submission) {
        if (submission == null) {
            throw new BusinessException(
                    PositionErrorCode.JD_CONTENT_EMPTY, "岗位提交内容不能为空");
        }
    }

    /** 防止直接调用时绕过配置 Bean 校验并传入非正数容量。 */
    private void validatePositiveLimit(int limit, String name) {
        if (limit <= 0) {
            throw new IllegalStateException(name + "必须大于 0");
        }
    }

    /** 允许零提交间隔但拒绝空值和负数，保持与解析配置校验一致。 */
    private void validateInterval(Duration interval) {
        if (interval == null || interval.isNegative()) {
            throw new IllegalStateException("岗位解析提交间隔配置无效");
        }
    }

    /**
     * 事务登记岗位所需的已提取、已规范化输入，不包含客户端身份或 Redis 状态。
     *
     * @param positionName 必填岗位名称
     * @param companyName 可空公司名称
     * @param location 可空工作地点
     * @param salaryRange 可空薪资描述
     * @param jobCategory 必填岗位大类编码或存储值
     * @param level 可空岗位职级
     * @param jdContent 必填规范化 JD 正文
     */
    public record PositionSubmission(
            String positionName,
            String companyName,
            String location,
            String salaryRange,
            String jobCategory,
            String level,
            String jdContent) {
    }

    /**
     * MySQL 岗位和当前任务登记成功后的应用层结果，供事务后事件和 HTTP 响应使用。
     *
     * @param positionId 已持久化岗位 ID
     * @param positionName 已持久化岗位名称
     * @param taskId 新建的当前任务 ID
     * @param queueOwner 服务端生成的 Redis 队列参与者
     * @param status 登记完成时的任务状态，当前为 WAITING
     */
    public record SubmittedTask(
            Long positionId,
            String positionName,
            Long taskId,
            String queueOwner,
            PositionAnalysisTaskStatus status) {
    }

    /**
     * 调度事务成功把任务领取为 RUNNING 后交给事务外 Worker 的不可变输入。
     *
     * @param taskId 已领取的当前任务 ID
     * @param positionId 任务所属岗位 ID
     * @param requestUserId 任务发起人审计 ID，不作为岗位资源授权依据
     * @param queueOwner 已由岗位事实重新校验的队列参与者
     * @param jdContent 数据库中的规范化 JD 正文
     * @param jobCategory 数据库中的岗位大类上下文
     */
    public record AnalysisInput(
            Long taskId,
            Long positionId,
            Long requestUserId,
            String queueOwner,
            String jdContent,
            String jobCategory) {
    }

    /**
     * 状态接口在 MySQL 只读事务中固定的岗位、当前任务和正式画像快照，Redis 等待量由事务外补充。
     *
     * @param positionId 被查询岗位 ID
     * @param archived 岗位是否已归档
     * @param taskId 当前任务 ID；无任务时为空
     * @param status 当前任务状态；无任务时为空
     * @param queueOwner 当前任务队列参与者；无任务时为空
     * @param errorCode 当前任务失败分类；非失败或无任务时通常为空
     * @param errorMessage 当前任务脱敏失败说明；非失败或无任务时通常为空
     * @param profileUsable 是否存在已确认正式画像，与候选任务状态相互独立
     */
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

    /** Worker 成功或失败写回对当前 MySQL 任务产生的确定结果。 */
    public enum CompletionOutcome {
        /** 同一当前 RUNNING 任务已成功写入请求的终态。 */
        APPLIED,

        /** 任务不存在、已被替换或不再 RUNNING，迟到结果未修改当前事实。 */
        STALE,

        /** 岗位在模型运行期间归档，当前任务已删除且模型结果被丢弃。 */
        DISCARDED_ARCHIVED
    }

    /**
     * 完成或失败事务按固定顺序锁定的岗位与当前 RUNNING 任务对。
     *
     * @param position 已悲观锁定的任务所属岗位
     * @param task 已悲观锁定且与 Worker taskId 匹配的当前 RUNNING 任务
     */
    private record LockedTask(Position position, PositionAnalysisTask task) {
    }
}
