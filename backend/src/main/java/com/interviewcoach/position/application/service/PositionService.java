package com.interviewcoach.position.application.service;

import static com.interviewcoach.position.application.service.PositionErrorCode.FILE_CONTENT_INVALID;
import static com.interviewcoach.position.application.service.PositionErrorCode.POSITION_NAME_EMPTY;
import static com.interviewcoach.position.application.service.PositionErrorCode.POSITION_NOT_FOUND;
import static com.interviewcoach.position.application.service.PositionErrorCode.POSITION_PAGE_INVALID;
import static com.interviewcoach.position.application.service.PositionErrorCode.PROFILE_DATA_INVALID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.interviewcoach.common.domain.JobCategoryType;
import com.interviewcoach.common.exception.BusinessException;
import com.interviewcoach.position.application.dto.PositionAnalysisStatusResponse;
import com.interviewcoach.position.application.dto.PositionCreateRequest;
import com.interviewcoach.position.application.dto.PositionCreateResponse;
import com.interviewcoach.position.application.dto.PositionDetailResponse;
import com.interviewcoach.position.application.dto.PositionListItemResponse;
import com.interviewcoach.position.application.dto.PositionListResponse;
import com.interviewcoach.position.application.dto.PositionProfileResponse;
import com.interviewcoach.position.application.port.PositionAnalysisQueueStatusReader;
import com.interviewcoach.position.application.service.PositionAnalysisStateService.PositionSubmission;
import com.interviewcoach.position.application.service.PositionAnalysisStateService.AnalysisStatusSnapshot;
import com.interviewcoach.position.application.service.PositionAnalysisStateService.SubmittedTask;
import com.interviewcoach.position.domain.entity.Position;
import com.interviewcoach.position.domain.entity.PositionAnalysisTask;
import com.interviewcoach.position.domain.entity.PositionAnalysisTaskStatus;
import com.interviewcoach.position.domain.entity.PositionLevel;
import com.interviewcoach.position.domain.entity.PositionProfile;
import com.interviewcoach.position.domain.model.PositionProfileData;
import com.interviewcoach.position.domain.repository.PositionAnalysisTaskRepository;
import com.interviewcoach.position.domain.repository.PositionProfileRepository;
import com.interviewcoach.position.domain.repository.PositionRepository;
import com.interviewcoach.position.infrastructure.config.PositionUploadProperties;
import com.interviewcoach.position.infrastructure.parser.JdFileExtractionService;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * 岗位 HTTP 用例编排服务：个人与公共写入口分离，读取状态由当前任务和正式画像共同生成。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PositionService {

    private static final int MAX_PAGE_SIZE = 100;

    private final PositionRepository positionRepository;
    private final PositionAnalysisTaskRepository taskRepository;
    private final PositionProfileRepository profileRepository;
    private final PositionAnalysisStateService analysisStateService;
    private final PositionSubmissionService submissionService;
    private final PositionLifecycleService lifecycleService;
    private final PositionAnalysisQueueStatusReader queueStatusReader;
    private final JdContentNormalizer contentNormalizer;
    private final JdFileExtractionService fileExtractionService;
    private final PositionUploadProperties uploadProperties;
    private final ObjectMapper objectMapper;

    /**
     * 先在事务外规范化粘贴文本，再用短事务登记个人岗位和 WAITING 任务。
     */
    public PositionCreateResponse createPosition(Long userId, PositionCreateRequest request) {
        String jdContent = contentNormalizer.normalizeAndValidate(
                request.getJdContent(), uploadProperties.getMaxCodePoints());
        return toCreateResponse(submissionService.submitPersonal(
                userId, toSubmission(request, jdContent)));
    }

    /**
     * 先在事务外完成临时文件提取与清理，再用短事务登记个人岗位和 WAITING 任务。
     */
    public PositionCreateResponse uploadPosition(
            Long userId, MultipartFile file, String fileType, String positionName) {
        String normalizedName = requireText(
                positionName, POSITION_NAME_EMPTY, "岗位名称不能为空");
        String jdContent = fileExtractionService.extract(file, fileType);
        return toCreateResponse(submissionService.submitPersonal(
                userId, uploadSubmission(normalizedName, jdContent)));
    }

    /**
     * 管理员公共创建使用独立入口和固定提交锁，不占个人岗位数量或频控。
     */
    public PositionCreateResponse createPublicPosition(
            Long adminId, PositionCreateRequest request) {
        String jdContent = contentNormalizer.normalizeAndValidate(
                request.getJdContent(), uploadProperties.getMaxCodePoints());
        return toCreateResponse(submissionService.submitPublic(
                adminId, toSubmission(request, jdContent)));
    }

    /**
     * 公共文件上传仍在事务外完成提取，只有有效文本才进入公共提交锁和数据库事务。
     */
    public PositionCreateResponse uploadPublicPosition(
            Long adminId, MultipartFile file, String fileType, String positionName) {
        String normalizedName = requireText(
                positionName, POSITION_NAME_EMPTY, "岗位名称不能为空");
        String jdContent = fileExtractionService.extract(file, fileType);
        return toCreateResponse(submissionService.submitPublic(
                adminId, uploadSubmission(normalizedName, jdContent)));
    }

    /**
     * 个人列表只返回本人个人岗位，并以 archived 参数在活动和归档视图间切换。
     */
    @Transactional(readOnly = true)
    public PositionListResponse listPositions(
            Long userId, int page, int size, boolean archived) {
        Pageable pageable = pageable(page, size);
        Page<Position> positions = archived
                ? positionRepository.findByUserIdAndIsPublicFalseAndArchivedAtIsNotNull(
                        userId, pageable)
                : positionRepository.findByUserIdAndIsPublicFalseAndArchivedAtIsNull(
                        userId, pageable);
        return toListResponse(positions, userId, false);
    }

    /**
     * 普通用户公共列表只返回已确认正式画像且未归档的公共岗位。
     */
    @Transactional(readOnly = true)
    public PositionListResponse listPublicPositions(int page, int size) {
        return toListResponse(
                positionRepository.findPublishedPublic(pageable(page, size)), null, false);
    }

    /**
     * 首页组合查询只返回正式画像可用的活动个人岗位和已发布公共岗位。
     */
    @Transactional(readOnly = true)
    public PositionListResponse listAccessiblePositions(Long userId, int page, int size) {
        return toListResponse(
                positionRepository.findAccessibleByUserId(userId, pageable(page, size)),
                userId,
                false);
    }

    /**
     * 管理员列表只管理公共岗位，并同样分开活动和归档视图。
     */
    @Transactional(readOnly = true)
    public PositionListResponse listPublicPositionsForAdmin(
            int page, int size, boolean archived) {
        Pageable pageable = pageable(page, size);
        Page<Position> positions = archived
                ? positionRepository.findByIsPublicTrueAndArchivedAtIsNotNull(pageable)
                : positionRepository.findByIsPublicTrueAndArchivedAtIsNull(pageable);
        return toListResponse(positions, null, true);
    }

    /**
     * 本人可查看个人归档记录；普通用户只能查看已发布且未归档的公共岗位。
     */
    @Transactional(readOnly = true)
    public PositionDetailResponse getPositionDetail(Long userId, Long positionId) {
        Position position = positionRepository.findVisibleById(positionId, userId)
                .orElseThrow(this::notFound);
        return toDetailResponse(position, userId, false);
    }

    /**
     * 管理员详情查询再次限制目标必须为公共岗位，不能借管理入口读取个人岗位。
     */
    @Transactional(readOnly = true)
    public PositionDetailResponse getPublicPositionDetailForAdmin(Long positionId) {
        Position position = positionRepository.findByIdAndIsPublicTrue(positionId)
                .orElseThrow(this::notFound);
        return toDetailResponse(position, null, true);
    }

    /**
     * 个人岗位所有者可同时查看正式画像和当前候选；公共普通用户只看到正式画像。
     */
    @Transactional(readOnly = true)
    public PositionProfileResponse getPositionProfile(Long userId, Long positionId) {
        Position position = positionRepository.findVisibleById(positionId, userId)
                .orElseThrow(this::notFound);
        return toProfileResponse(position, userId, false);
    }

    /**
     * 管理员可查看公共岗位候选并据 taskId 确认，但不能读取个人岗位候选。
     */
    @Transactional(readOnly = true)
    public PositionProfileResponse getPublicPositionProfileForAdmin(Long positionId) {
        Position position = positionRepository.findByIdAndIsPublicTrue(positionId)
                .orElseThrow(this::notFound);
        return toProfileResponse(position, null, true);
    }

    /**
     * 个人轻量状态接口只接受本人个人岗位，避免暴露他人或公共管理任务。
     */
    public PositionAnalysisStatusResponse getAnalysisStatus(Long userId, Long positionId) {
        return toAnalysisStatusResponse(
                analysisStateService.loadPersonalAnalysisStatus(userId, positionId));
    }

    /**
     * 管理员轻量状态接口只读取公共岗位任务。
     */
    public PositionAnalysisStatusResponse getPublicAnalysisStatusForAdmin(Long positionId) {
        return toAnalysisStatusResponse(
                analysisStateService.loadPublicAnalysisStatus(positionId));
    }

    /**
     * 只确认本人个人岗位的当前成功候选，旧 taskId 由短事务状态服务拒绝。
     */
    public void confirmPosition(
            Long userId, Long positionId, Long taskId, PositionProfileData profileData) {
        analysisStateService.confirmPersonalCandidate(
                userId, positionId, taskId, profileData);
    }

    /**
     * 只确认当前公共岗位候选，管理员角色与公共资源约束分别由入口和状态服务保证。
     */
    public void confirmPublicPosition(
            Long positionId, Long taskId, PositionProfileData profileData) {
        analysisStateService.confirmPublicCandidate(positionId, taskId, profileData);
    }

    /**
     * 为本人活动个人岗位替换终态任务，并继续执行个人等待数和提交间隔限制。
     */
    public PositionCreateResponse reparsePosition(Long userId, Long positionId) {
        return toCreateResponse(submissionService.reparsePersonal(userId, positionId));
    }

    /**
     * 在公共提交锁内为活动公共岗位替换终态任务。
     */
    public PositionCreateResponse reparsePublicPosition(Long adminId, Long positionId) {
        return toCreateResponse(submissionService.reparsePublic(adminId, positionId));
    }

    /**
     * 幂等归档本人个人岗位。
     */
    public void archivePosition(Long userId, Long positionId) {
        lifecycleService.archivePersonal(userId, positionId);
    }

    /**
     * 幂等归档公共岗位并立即停止普通用户公开读取。
     */
    public void archivePublicPosition(Long positionId) {
        lifecycleService.archivePublic(positionId);
    }

    /**
     * 永久删除本人已归档且通过运行任务和面试守卫的个人岗位。
     */
    public void deletePosition(Long userId, Long positionId) {
        lifecycleService.deletePersonal(userId, positionId);
    }

    /**
     * 永久删除已归档且通过运行任务和面试守卫的公共岗位。
     */
    public void deletePublicPosition(Long positionId) {
        lifecycleService.deletePublic(positionId);
    }

    private PositionSubmission toSubmission(PositionCreateRequest request, String jdContent) {
        return new PositionSubmission(
                requireText(request.getPositionName(), POSITION_NAME_EMPTY, "岗位名称不能为空"),
                request.getCompanyName(),
                request.getLocation(),
                request.getSalaryRange(),
                requireText(request.getJobCategory(), PROFILE_DATA_INVALID, "岗位类别不能为空"),
                request.getLevel(),
                jdContent);
    }

    private PositionSubmission uploadSubmission(String positionName, String jdContent) {
        return new PositionSubmission(
                positionName,
                null,
                null,
                null,
                inferJobCategory(jdContent),
                null,
                jdContent);
    }

    /**
     * 批量加载当前任务和正式画像，避免列表 N+1；公共普通用户的任务摘要在映射时隐藏。
     */
    private PositionListResponse toListResponse(
            Page<Position> positionPage, Long userId, boolean admin) {
        List<Long> positionIds = positionPage.getContent().stream()
                .map(Position::getId)
                .toList();
        Map<Long, PositionAnalysisTask> tasks = positionIds.isEmpty()
                ? Map.of()
                : taskRepository.findByPositionIdIn(positionIds).stream()
                        .collect(Collectors.toMap(
                                PositionAnalysisTask::getPositionId, Function.identity()));
        Set<Long> positionsWithProfile = positionIds.isEmpty()
                ? Set.of()
                : profileRepository.findByPositionIdIn(positionIds).stream()
                        .map(PositionProfile::getPositionId)
                        .collect(Collectors.toSet());

        PositionListResponse response = new PositionListResponse();
        response.setContent(positionPage.getContent().stream()
                .map(position -> toListItem(
                        position,
                        visibleTask(position, tasks.get(position.getId()), userId, admin),
                        positionsWithProfile.contains(position.getId()),
                        canManage(position, userId, admin)))
                .toList());
        response.setTotalElements(positionPage.getTotalElements());
        response.setTotalPages(positionPage.getTotalPages());
        response.setCurrentPage(positionPage.getNumber());
        return response;
    }

    private PositionListItemResponse toListItem(
            Position position,
            PositionAnalysisTask task,
            boolean profileUsable,
            boolean manageable) {
        AnalysisView analysis = analysisView(position, task, profileUsable, manageable);
        PositionListItemResponse item = new PositionListItemResponse();
        item.setPositionId(position.getId());
        item.setPositionName(position.getPositionName());
        item.setCompanyName(position.getCompanyName());
        item.setJobCategory(position.getJobCategory());
        item.setJobCategoryLabel(JobCategoryType.displayNameOf(position.getJobCategory()));
        item.setLevel(position.getLevel());
        item.setLevelLabel(PositionLevel.displayNameOf(position.getLevel()));
        item.setJdContent(position.getJdContent());
        item.setIsPublic(position.getIsPublic());
        item.setUserId(position.getUserId());
        item.setArchived(position.isArchived());
        item.setArchivedAt(position.getArchivedAt());
        item.setLatestTaskId(analysis.taskId());
        item.setLatestTaskStatus(analysis.status());
        item.setLatestTaskStatusLabel(analysis.statusLabel());
        item.setProfileUsable(analysis.profileUsable());
        item.setCanConfirm(analysis.canConfirm());
        item.setCanRetry(analysis.canRetry());
        item.setAnalysisErrorCode(analysis.errorCode());
        item.setAnalysisErrorMessage(analysis.errorMessage());
        item.setCreatedAt(position.getCreatedAt());
        item.setUpdatedAt(position.getUpdatedAt());
        return item;
    }

    private PositionDetailResponse toDetailResponse(
            Position position, Long userId, boolean admin) {
        boolean manageable = canManage(position, userId, admin);
        PositionAnalysisTask task = visibleTask(
                position,
                taskRepository.findByPositionId(position.getId()).orElse(null),
                userId,
                admin);
        boolean profileUsable = profileRepository.existsByPositionId(position.getId());
        AnalysisView analysis = analysisView(position, task, profileUsable, manageable);

        PositionDetailResponse response = new PositionDetailResponse();
        response.setPositionId(position.getId());
        response.setPositionName(position.getPositionName());
        response.setCompanyName(position.getCompanyName());
        response.setLocation(position.getLocation());
        response.setSalaryRange(position.getSalaryRange());
        response.setJobCategory(position.getJobCategory());
        response.setJobCategoryLabel(JobCategoryType.displayNameOf(position.getJobCategory()));
        response.setLevel(position.getLevel());
        response.setLevelLabel(PositionLevel.displayNameOf(position.getLevel()));
        response.setJdContent(position.getJdContent());
        response.setIsPublic(position.getIsPublic());
        response.setUserId(position.getUserId());
        response.setArchived(position.isArchived());
        response.setArchivedAt(position.getArchivedAt());
        response.setLatestTaskId(analysis.taskId());
        response.setLatestTaskStatus(analysis.status());
        response.setLatestTaskStatusLabel(analysis.statusLabel());
        response.setProfileUsable(analysis.profileUsable());
        response.setCanConfirm(analysis.canConfirm());
        response.setCanRetry(analysis.canRetry());
        response.setAnalysisErrorCode(analysis.errorCode());
        response.setAnalysisErrorMessage(analysis.errorMessage());
        response.setCreatedAt(position.getCreatedAt());
        response.setUpdatedAt(position.getUpdatedAt());
        return response;
    }

    private PositionProfileResponse toProfileResponse(
            Position position, Long userId, boolean admin) {
        boolean manageable = canManage(position, userId, admin);
        PositionAnalysisTask task = visibleTask(
                position,
                taskRepository.findByPositionId(position.getId()).orElse(null),
                userId,
                admin);
        PositionProfile profile = profileRepository.findByPositionId(position.getId()).orElse(null);
        AnalysisView analysis = analysisView(position, task, profile != null, manageable);

        PositionProfileResponse response = new PositionProfileResponse();
        response.setPositionId(position.getId());
        response.setProfileId(profile == null ? null : profile.getId());
        response.setProfile(profile == null
                ? null
                : parseStoredProfile(profile.getProfileData(), position.getId(), "formal"));
        response.setTaskId(analysis.taskId());
        response.setLatestTaskStatus(analysis.status());
        response.setLatestTaskStatusLabel(analysis.statusLabel());
        if (task != null && task.getStatus() == PositionAnalysisTaskStatus.SUCCEEDED) {
            response.setCandidateProfile(parseStoredProfile(
                    task.getCandidateProfileData(), position.getId(), "candidate"));
        }
        response.setAnalysisErrorCode(analysis.errorCode());
        response.setAnalysisErrorMessage(analysis.errorMessage());
        response.setProfileUsable(analysis.profileUsable());
        response.setCanConfirm(analysis.canConfirm());
        response.setCanRetry(analysis.canRetry());
        response.setArchived(position.isArchived());
        return response;
    }

    private PositionAnalysisStatusResponse toAnalysisStatusResponse(
            AnalysisStatusSnapshot snapshot) {
        PositionAnalysisTaskStatus status = snapshot.status();
        boolean active = !snapshot.archived();
        PositionAnalysisStatusResponse response = new PositionAnalysisStatusResponse();
        response.setPositionId(snapshot.positionId());
        response.setTaskId(snapshot.taskId());
        response.setLatestTaskStatus(status == null ? null : status.name());
        response.setLatestTaskStatusLabel(status == null ? null : status.getDisplayName());
        if (status == PositionAnalysisTaskStatus.WAITING) {
            response.setQueueAhead(readQueueAhead(snapshot.taskId(), snapshot.queueOwner()));
        }
        response.setAnalysisErrorCode(
                status == PositionAnalysisTaskStatus.FAILED ? snapshot.errorCode() : null);
        response.setAnalysisErrorMessage(
                status == PositionAnalysisTaskStatus.FAILED ? snapshot.errorMessage() : null);
        response.setProfileUsable(snapshot.profileUsable());
        response.setCanConfirm(active && status == PositionAnalysisTaskStatus.SUCCEEDED);
        response.setCanRetry(active && (status == null
                || status == PositionAnalysisTaskStatus.SUCCEEDED
                || status == PositionAnalysisTaskStatus.FAILED));
        response.setArchived(snapshot.archived());
        return response;
    }

    /**
     * 可操作能力只由资源类型、归属、归档状态和当前四状态任务推导，不读取遗留审核列。
     */
    private AnalysisView analysisView(
            Position position,
            PositionAnalysisTask task,
            boolean profileUsable,
            boolean manageable) {
        PositionAnalysisTaskStatus status = task == null ? null : task.getStatus();
        boolean active = !position.isArchived();
        boolean canConfirm = manageable && active && status == PositionAnalysisTaskStatus.SUCCEEDED;
        boolean canRetry = manageable && active && (status == null
                || status == PositionAnalysisTaskStatus.SUCCEEDED
                || status == PositionAnalysisTaskStatus.FAILED);
        boolean failed = status == PositionAnalysisTaskStatus.FAILED;
        return new AnalysisView(
                task == null ? null : task.getId(),
                status == null ? null : status.name(),
                status == null ? null : status.getDisplayName(),
                profileUsable,
                canConfirm,
                canRetry,
                failed ? task.getErrorCode() : null,
                failed ? task.getErrorMessage() : null);
    }

    private PositionAnalysisTask visibleTask(
            Position position,
            PositionAnalysisTask task,
            Long userId,
            boolean admin) {
        return canManage(position, userId, admin) ? task : null;
    }

    private boolean canManage(Position position, Long userId, boolean admin) {
        if (admin) {
            return Boolean.TRUE.equals(position.getIsPublic());
        }
        return Boolean.FALSE.equals(position.getIsPublic())
                && Objects.equals(position.getUserId(), userId);
    }

    /**
     * Redis 等待量不可用时保留 MySQL 任务状态并返回空估算，不把状态接口整体降级为失败。
     */
    private Long readQueueAhead(Long taskId, String queueOwner) {
        try {
            return queueStatusReader.queueAhead(taskId, queueOwner);
        } catch (RuntimeException e) {
            log.warn("[PositionAnalysis] 等待量读取失败: taskId={}, errorType={}",
                    taskId, e.getClass().getSimpleName());
            return null;
        }
    }

    /**
     * 兼容既有字符串字面量存储，但解析失败必须显式报错，不能伪造空画像。
     */
    private PositionProfileData parseStoredProfile(
            String json, Long positionId, String source) {
        if (json == null || json.isBlank()) {
            throw new BusinessException(PROFILE_DATA_INVALID, "岗位画像数据异常");
        }
        String normalized = json.trim();
        if (normalized.startsWith("\"") && normalized.endsWith("\"")) {
            try {
                normalized = objectMapper.readValue(normalized, String.class);
            } catch (JsonProcessingException e) {
                log.error("[Position] 画像字符串字面量解码失败: positionId={}, source={}",
                        positionId, source, e);
                throw new BusinessException(PROFILE_DATA_INVALID, "岗位画像数据异常", e);
            }
        }
        try {
            return objectMapper.readValue(normalized, PositionProfileData.class);
        } catch (JsonProcessingException e) {
            log.error("[Position] 画像 JSON 解析失败: positionId={}, source={}",
                    positionId, source, e);
            throw new BusinessException(PROFILE_DATA_INVALID, "岗位画像数据异常", e);
        }
    }

    private PositionCreateResponse toCreateResponse(SubmittedTask submitted) {
        return new PositionCreateResponse(
                submitted.positionId(),
                submitted.positionName(),
                submitted.taskId(),
                submitted.status().name());
    }

    private Pageable pageable(int page, int size) {
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new BusinessException(
                    POSITION_PAGE_INVALID,
                    "分页参数无效，页码不能为负且每页数量必须在 1 到 100 之间");
        }
        return PageRequest.of(
                page,
                size,
                Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
    }

    private String requireText(String value, int errorCode, String message) {
        if (value == null || value.isBlank()) {
            throw new BusinessException(errorCode, message);
        }
        return value.trim();
    }

    /**
     * 上传接口没有独立岗位类别字段，按 JD 关键词给出当前 MVP 的服务端默认分类。
     */
    private String inferJobCategory(String jdText) {
        if (jdText == null) {
            throw new BusinessException(FILE_CONTENT_INVALID, "JD 文件内容无效");
        }
        String text = jdText.toLowerCase();
        if (text.contains("java") || text.contains("python") || text.contains("go")
                || text.contains("后端") || text.contains("前端") || text.contains("算法")) {
            return "TECH";
        }
        if (text.contains("产品") || text.contains("产品经理") || text.contains("prd")) {
            return "PRODUCT";
        }
        if (text.contains("设计") || text.contains("ui") || text.contains("ux")) {
            return "DESIGN";
        }
        if (text.contains("运营") || text.contains("增长") || text.contains("活动")) {
            return "OPERATION";
        }
        return "TECH";
    }

    private BusinessException notFound() {
        return new BusinessException(POSITION_NOT_FOUND, "岗位不存在");
    }

    private record AnalysisView(
            Long taskId,
            String status,
            String statusLabel,
            boolean profileUsable,
            boolean canConfirm,
            boolean canRetry,
            String errorCode,
            String errorMessage) {
    }
}
