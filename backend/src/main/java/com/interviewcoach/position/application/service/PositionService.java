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
 * Controller 把可信用户或管理员入口数据交给本服务；数据库查询和状态服务继续落实资源归属、公共可见性与任务代次。
 * 文本/文件处理和 Redis 等待量读取位于写事务外，模型候选与正式画像保持为两个独立版本。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PositionService {

    /**
     * 岗位列表单页允许的当前固定最大记录数 100；调大增加数据库读取和响应体，调小更早拒绝请求，精确取值依据缺失。
     */
    private static final int MAX_PAGE_SIZE = 100;

    /** 查询带个人归属或公共可见性条件的岗位记录。 */
    private final PositionRepository positionRepository;
    /** 批量或单项读取岗位唯一当前任务，供响应生成任务摘要。 */
    private final PositionAnalysisTaskRepository taskRepository;
    /** 读取已确认正式画像及其存在性，候选画像不经此仓储读取。 */
    private final PositionProfileRepository profileRepository;
    /** 执行 MySQL 当前任务状态读取和候选确认事务。 */
    private final PositionAnalysisStateService analysisStateService;
    /** 编排个人或公共登记、固定提交锁和事务后入队事件。 */
    private final PositionSubmissionService submissionService;
    /** 执行个人或公共岗位的归档、投影移除和受保护永久删除。 */
    private final PositionLifecycleService lifecycleService;
    /** 从 Redis 投影读取非承诺的等待量估算，失败时允许状态接口降级为空值。 */
    private final PositionAnalysisQueueStatusReader queueStatusReader;
    /** 为粘贴 JD 执行 BOM、换行、空白和 code point 规范化校验。 */
    private final JdContentNormalizer contentNormalizer;
    /** 将上传 PDF/TXT 转为受限文本并负责临时文件生命周期。 */
    private final JdFileExtractionService fileExtractionService;
    /** 提供 JD code point 上限及文件提取限制的上传配置。 */
    private final PositionUploadProperties uploadProperties;
    /** 反序列化数据库正式或候选画像 JSON，并兼容既有字符串字面量。 */
    private final ObjectMapper objectMapper;

    /**
     * 先在事务外规范化粘贴文本，再用短事务登记个人岗位和 WAITING 任务。
     */
    public PositionCreateResponse createPosition(Long userId, PositionCreateRequest request) {
        // 在事务外统一粘贴文本格式并执行最终字符上限，失败时不会登记岗位或消耗提交频控。
        String jdContent = contentNormalizer.normalizeAndValidate(
                request.getJdContent(), uploadProperties.getMaxCodePoints());
        // 将规范化请求交给个人提交服务，在 MySQL 事务登记岗位与 WAITING 后返回轮询标识。
        return toCreateResponse(submissionService.submitPersonal(
                userId, toSubmission(request, jdContent)));
    }

    /**
     * 先在事务外完成临时文件提取与清理，再用短事务登记个人岗位和 WAITING 任务。
     */
    public PositionCreateResponse uploadPosition(
            Long userId, MultipartFile file, String fileType, String positionName) {
        // 先校验并规范化独立的岗位名称，避免无效请求创建临时提取任务。
        String normalizedName = requireText(
                positionName, POSITION_NAME_EMPTY, "岗位名称不能为空");
        // 在事务外流式复制、真实格式校验、提取并清理临时文件，失败不进入岗位登记。
        String jdContent = fileExtractionService.extract(file, fileType);
        // 文件接口缺少其他结构字段，构造当前 MVP 默认提交后登记本人岗位和 WAITING。
        return toCreateResponse(submissionService.submitPersonal(
                userId, uploadSubmission(normalizedName, jdContent)));
    }

    /**
     * 管理员公共创建使用独立入口和固定提交锁，不占个人岗位数量或频控。
     */
    public PositionCreateResponse createPublicPosition(
            Long adminId, PositionCreateRequest request) {
        // 在进入公共提交锁和数据库事务前规范化 JD，输入失败不会占用公共临界区。
        String jdContent = contentNormalizer.normalizeAndValidate(
                request.getJdContent(), uploadProperties.getMaxCodePoints());
        // 管理员角色由 HTTP 入口保证；提交服务仍使用固定 PUBLIC 锁并在事务中限制公共类型和容量。
        return toCreateResponse(submissionService.submitPublic(
                adminId, toSubmission(request, jdContent)));
    }

    /**
     * 公共文件上传仍在事务外完成提取，只有有效文本才进入公共提交锁和数据库事务。
     */
    public PositionCreateResponse uploadPublicPosition(
            Long adminId, MultipartFile file, String fileType, String positionName) {
        // 先校验名称并在事务外完成受限文件提取，避免持锁期间执行文件 I/O。
        String normalizedName = requireText(
                positionName, POSITION_NAME_EMPTY, "岗位名称不能为空");
        String jdContent = fileExtractionService.extract(file, fileType);
        // 仅提取成功的内容进入固定 PUBLIC 锁和公共岗位登记事务，不占管理员个人额度。
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
        // 仓储查询同时限制可信用户、非公共类型和归档视图，不返回其他用户或公共岗位。
        Page<Position> positions = archived
                ? positionRepository.findByUserIdAndIsPublicFalseAndArchivedAtIsNotNull(
                        userId, pageable)
                : positionRepository.findByUserIdAndIsPublicFalseAndArchivedAtIsNull(
                        userId, pageable);
        // 批量补当前任务与正式画像存在性；只有本人可见任务和操作能力。
        return toListResponse(positions, userId, false);
    }

    /**
     * 普通用户公共列表只返回已确认正式画像且未归档的公共岗位。
     */
    @Transactional(readOnly = true)
    public PositionListResponse listPublicPositions(int page, int size) {
        // 仓储仅返回正式画像存在的活动公共岗位，响应映射会继续隐藏当前任务、候选和失败字段。
        return toListResponse(
                positionRepository.findPublishedPublic(pageable(page, size)), null, false);
    }

    /**
     * 首页组合查询只返回正式画像可用的活动个人岗位和已发布公共岗位。
     */
    @Transactional(readOnly = true)
    public PositionListResponse listAccessiblePositions(Long userId, int page, int size) {
        // 查询按可信用户组合本人个人岗位和公共岗位，并要求活动且正式画像可用。
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
        // 管理端角色由入口保证，仓储仍限制 is_public=true 并按归档状态拆分视图。
        Page<Position> positions = archived
                ? positionRepository.findByIsPublicTrueAndArchivedAtIsNotNull(pageable)
                : positionRepository.findByIsPublicTrueAndArchivedAtIsNull(pageable);
        // admin 标记只允许公共记录暴露任务管理字段，不会使个人岗位变得可管理。
        return toListResponse(positions, null, true);
    }

    /**
     * 本人可查看个人归档记录；普通用户只能查看已发布且未归档的公共岗位。
     */
    @Transactional(readOnly = true)
    public PositionDetailResponse getPositionDetail(Long userId, Long positionId) {
        // 查询允许本人个人岗位含归档记录，或活动且正式画像可用的公共岗位；其余统一按不存在处理。
        Position position = positionRepository.findVisibleById(positionId, userId)
                .orElseThrow(this::notFound);
        // 映射时再次根据资源可管理性决定是否暴露任务、失败和操作能力字段。
        return toDetailResponse(position, userId, false);
    }

    /**
     * 管理员详情查询再次限制目标必须为公共岗位，不能借管理入口读取个人岗位。
     */
    @Transactional(readOnly = true)
    public PositionDetailResponse getPublicPositionDetailForAdmin(Long positionId) {
        // 管理角色由入口保证，仓储仍限制目标必须为公共岗位并允许查看归档状态。
        Position position = positionRepository.findByIdAndIsPublicTrue(positionId)
                .orElseThrow(this::notFound);
        // admin 视图可读取公共当前任务摘要，但不会借此读取个人岗位。
        return toDetailResponse(position, null, true);
    }

    /**
     * 个人岗位所有者可同时查看正式画像和当前候选；公共普通用户只看到正式画像。
     */
    @Transactional(readOnly = true)
    public PositionProfileResponse getPositionProfile(Long userId, Long positionId) {
        // 先按本人归属或公共发布条件确定资源可见性，避免按客户端 ID 直接读取任意画像。
        Position position = positionRepository.findVisibleById(positionId, userId)
                .orElseThrow(this::notFound);
        // 本人个人岗位可见候选，公共普通读取映射只保留正式画像和隐藏后的能力字段。
        return toProfileResponse(position, userId, false);
    }

    /**
     * 管理员可查看公共岗位候选并据 taskId 确认，但不能读取个人岗位候选。
     */
    @Transactional(readOnly = true)
    public PositionProfileResponse getPublicPositionProfileForAdmin(Long positionId) {
        // 管理入口继续以公共类型条件读取岗位，个人岗位即使 ID 存在也按不存在处理。
        Position position = positionRepository.findByIdAndIsPublicTrue(positionId)
                .orElseThrow(this::notFound);
        // admin 视图可读取公共成功候选和精确 taskId，供后续确认事务校验代次。
        return toProfileResponse(position, null, true);
    }

    /**
     * 个人轻量状态接口只接受本人个人岗位，避免暴露他人或公共管理任务。
     */
    public PositionAnalysisStatusResponse getAnalysisStatus(Long userId, Long positionId) {
        // 先在 MySQL 只读事务固定本人岗位状态，再在事务外按需查询 Redis 等待量。
        return toAnalysisStatusResponse(
                analysisStateService.loadPersonalAnalysisStatus(userId, positionId));
    }

    /**
     * 管理员轻量状态接口只读取公共岗位任务。
     */
    public PositionAnalysisStatusResponse getPublicAnalysisStatusForAdmin(Long positionId) {
        // 管理角色由入口保证，状态服务仍限制公共资源；Redis 估算在 MySQL 快照返回后补充。
        return toAnalysisStatusResponse(
                analysisStateService.loadPublicAnalysisStatus(positionId));
    }

    /**
     * 只确认本人个人岗位的当前成功候选，旧 taskId 由短事务状态服务拒绝。
     */
    public void confirmPosition(
            Long userId, Long positionId, Long taskId, PositionProfileData profileData) {
        // 状态事务锁定本人岗位和当前任务，要求 taskId 精确匹配成功候选，再替换正式画像并删除任务。
        analysisStateService.confirmPersonalCandidate(
                userId, positionId, taskId, profileData);
    }

    /**
     * 只确认当前公共岗位候选，管理员角色与公共资源约束分别由入口和状态服务保证。
     */
    public void confirmPublicPosition(
            Long positionId, Long taskId, PositionProfileData profileData) {
        // 管理入口角色与状态事务的公共类型守卫共同生效，成功后公共正式画像立即可被公开查询。
        analysisStateService.confirmPublicCandidate(positionId, taskId, profileData);
    }

    /**
     * 为本人活动个人岗位替换终态任务，并继续执行个人等待数和提交间隔限制。
     */
    public PositionCreateResponse reparsePosition(Long userId, Long positionId) {
        // 提交服务在本人归属、活动状态、终态替换、个人等待容量和提交间隔全部通过后登记新任务。
        return toCreateResponse(submissionService.reparsePersonal(userId, positionId));
    }

    /**
     * 在公共提交锁内为活动公共岗位替换终态任务。
     */
    public PositionCreateResponse reparsePublicPosition(Long adminId, Long positionId) {
        // 提交服务持有固定 PUBLIC 锁完成公共类型、任务源状态和等待容量检查，再登记新任务。
        return toCreateResponse(submissionService.reparsePublic(adminId, positionId));
    }

    /**
     * 幂等归档本人个人岗位。
     */
    public void archivePosition(Long userId, Long positionId) {
        // 生命周期服务锁定可信用户和本人岗位，写归档时间并按任务状态删除或保留当前任务。
        lifecycleService.archivePersonal(userId, positionId);
    }

    /**
     * 幂等归档公共岗位并立即停止普通用户公开读取。
     */
    public void archivePublicPosition(Long positionId) {
        // 管理角色由入口保证，生命周期服务仍锁定公共岗位并在事务提交后清理可删除 WAITING 投影。
        lifecycleService.archivePublic(positionId);
    }

    /**
     * 永久删除本人已归档且通过运行任务和面试守卫的个人岗位。
     */
    public void deletePosition(Long userId, Long positionId) {
        // 生命周期服务最终校验本人归属、已归档、无 RUNNING 和无进行中面试，再删除当前任务、画像和岗位。
        lifecycleService.deletePersonal(userId, positionId);
    }

    /**
     * 永久删除已归档且通过运行任务和面试守卫的公共岗位。
     */
    public void deletePublicPosition(Long positionId) {
        // 管理入口角色与公共类型锁共同生效；历史面试读取快照，因此不会随岗位物理删除。
        lifecycleService.deletePublic(positionId);
    }

    /** 将已完成 HTTP 校验的结构请求和规范化 JD 转换为事务登记输入，并再次拒绝空名称或类别。 */
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

    /** 为只有名称和文件内容的上传入口构造提交，缺失字段置空并按当前关键词规则推断岗位大类。 */
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
        // 一次批量读取当前任务并按 positionId 建图，避免列表为每个岗位单独查询。
        Map<Long, PositionAnalysisTask> tasks = positionIds.isEmpty()
                ? Map.of()
                : taskRepository.findByPositionIdIn(positionIds).stream()
                        .collect(Collectors.toMap(
                                PositionAnalysisTask::getPositionId, Function.identity()));
        // 一次批量读取正式画像 ID 集合，只判断 profileUsable，不在列表反序列化完整画像。
        Set<Long> positionsWithProfile = positionIds.isEmpty()
                ? Set.of()
                : profileRepository.findByPositionIdIn(positionIds).stream()
                        .map(PositionProfile::getPositionId)
                        .collect(Collectors.toSet());

        PositionListResponse response = new PositionListResponse();
        // 每项先根据调用者管理边界隐藏任务，再从岗位、任务和正式画像存在性推导能力字段。
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

    /** 把已授权可见的岗位、任务摘要和正式画像存在性映射为单条列表响应。 */
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

    /** 读取当前任务与正式画像存在性，并按调用场景隐藏不可管理任务后映射详情响应。 */
    private PositionDetailResponse toDetailResponse(
            Position position, Long userId, boolean admin) {
        boolean manageable = canManage(position, userId, admin);
        // 当前任务从 MySQL 读取后先应用个人归属或公共管理边界，公共普通读取固定隐藏。
        PositionAnalysisTask task = visibleTask(
                position,
                taskRepository.findByPositionId(position.getId()).orElse(null),
                userId,
                admin);
        // 正式画像存在性独立于当前任务状态，即使重新解析等待或失败也可继续为 true。
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

    /** 分别读取正式画像和可见成功候选，反序列化为两个版本并生成确认、重试能力。 */
    private PositionProfileResponse toProfileResponse(
            Position position, Long userId, boolean admin) {
        boolean manageable = canManage(position, userId, admin);
        // 先按资源管理边界隐藏任务，普通公共读取不会得到候选、失败或 taskId。
        PositionAnalysisTask task = visibleTask(
                position,
                taskRepository.findByPositionId(position.getId()).orElse(null),
                userId,
                admin);
        // 正式画像从独立表读取；不存在时仍可返回当前任务和候选状态给有管理权的调用者。
        PositionProfile profile = profileRepository.findByPositionId(position.getId()).orElse(null);
        AnalysisView analysis = analysisView(position, task, profile != null, manageable);

        PositionProfileResponse response = new PositionProfileResponse();
        response.setPositionId(position.getId());
        response.setProfileId(profile == null ? null : profile.getId());
        // 正式画像解析失败显式报数据错误，不能伪造空画像或改用候选替代。
        response.setProfile(profile == null
                ? null
                : parseStoredProfile(profile.getProfileData(), position.getId(), "formal"));
        response.setTaskId(analysis.taskId());
        response.setLatestTaskStatus(analysis.status());
        response.setLatestTaskStatusLabel(analysis.statusLabel());
        if (task != null && task.getStatus() == PositionAnalysisTaskStatus.SUCCEEDED) {
            // 只有可见的当前成功任务才解析候选；候选与正式画像保持两个独立版本。
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

    /** 把 MySQL 状态快照转换为轻量轮询响应，并仅为 WAITING 尝试补充 Redis 等待量估算。 */
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
            // Redis 投影只提供瞬时估算；读取失败或任务不在队列时保留 MySQL 状态并返回空值。
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

    /** 只有本人个人岗位或管理员公共岗位管理场景可见任务；其他调用者固定得到空。 */
    private PositionAnalysisTask visibleTask(
            Position position,
            PositionAnalysisTask task,
            Long userId,
            boolean admin) {
        return canManage(position, userId, admin) ? task : null;
    }

    /** 根据可信入口类型和岗位数据库归属判断是否可管理，不使用任务发起人或画像 userId 授权。 */
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
            // 读取 Redis ready/busy/rank 快照；返回值只表示当前估算，不承诺开始或完成时间。
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
                // 兼容早期把 JSON 作为字符串字面量再次编码的存储，但解码失败不能吞掉坏数据。
                normalized = objectMapper.readValue(normalized, String.class);
            } catch (JsonProcessingException e) {
                log.error("[Position] 画像字符串字面量解码失败: positionId={}, source={}",
                        positionId, source, e);
                throw new BusinessException(PROFILE_DATA_INVALID, "岗位画像数据异常", e);
            }
        }
        try {
            // 将正式或候选 JSON 转为同一领域模型；未知或坏结构按项目 ObjectMapper 行为处理。
            return objectMapper.readValue(normalized, PositionProfileData.class);
        } catch (JsonProcessingException e) {
            log.error("[Position] 画像 JSON 解析失败: positionId={}, source={}",
                    positionId, source, e);
            throw new BusinessException(PROFILE_DATA_INVALID, "岗位画像数据异常", e);
        }
    }

    /** 将已提交任务结果转换为创建响应，状态使用现有稳定英文枚举编码。 */
    private PositionCreateResponse toCreateResponse(SubmittedTask submitted) {
        return new PositionCreateResponse(
                submitted.positionId(),
                submitted.positionName(),
                submitted.taskId(),
                submitted.status().name());
    }

    /** 校验零基页码和每页 1 至 100 条，并以创建时间、ID 倒序形成稳定分页。 */
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

    /** 去除首尾空白并按调用方指定错误码拒绝空文本。 */
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

    /** 为归属、公共可见性或资源不存在的未命中统一生成岗位不存在错误，避免泄漏具体原因。 */
    private BusinessException notFound() {
        return new BusinessException(POSITION_NOT_FOUND, "岗位不存在");
    }

    /**
     * 从岗位、可见当前任务和正式画像状态推导出的统一响应视图。
     *
     * @param taskId 调用方可见的当前任务 ID；任务被隐藏或不存在时为空
     * @param status 可见任务的稳定英文状态编码
     * @param statusLabel 可见任务状态的现有中文展示名
     * @param profileUsable 是否存在正式画像，与任务状态独立
     * @param canConfirm 是否具备管理权、岗位活动且当前任务成功
     * @param canRetry 是否具备管理权、岗位活动且当前无任务或任务已终结
     * @param errorCode 仅失败任务可见的稳定错误分类
     * @param errorMessage 仅失败任务可见的脱敏说明
     */
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
