package com.interviewcoach.position.application.service;

import static com.interviewcoach.position.application.service.PositionErrorCode.*;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.interviewcoach.common.exception.BusinessException;
import com.interviewcoach.position.application.dto.AuditPositionRequest;
import com.interviewcoach.position.application.dto.ConfirmPositionRequest;
import com.interviewcoach.position.application.dto.PositionCreateRequest;
import com.interviewcoach.position.application.dto.PositionCreateResponse;
import com.interviewcoach.position.application.dto.PositionDetailResponse;
import com.interviewcoach.position.application.dto.PositionListItemResponse;
import com.interviewcoach.position.application.dto.PositionListResponse;
import com.interviewcoach.position.application.dto.PositionProfileResponse;
import com.interviewcoach.position.domain.agent.JdAnalysisAgent;
import com.interviewcoach.position.domain.entity.Position;
import com.interviewcoach.position.domain.entity.PositionAuditStatus;
import com.interviewcoach.position.domain.entity.PositionParseStatus;
import com.interviewcoach.position.domain.entity.PositionProfile;
import com.interviewcoach.position.domain.model.PositionProfileData;
import com.interviewcoach.position.domain.repository.PositionProfileRepository;
import com.interviewcoach.position.domain.repository.PositionRepository;
import com.interviewcoach.position.infrastructure.parser.JdTextExtractor;
import com.interviewcoach.position.infrastructure.storage.JdFileStorageService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * 岗位准备流程的应用入口。
 *
 * <p>上游由岗位接口调用；本服务负责保存粘贴或上传的 JD、组织画像生成、保存用户确认结果以及处理审核状态。
 * 文件读取交给 {@link JdTextExtractor}，画像生成和降级交给 {@link JdAnalysisAgent}，最终结果写入岗位及画像仓储，
 * 供后续创建面试时读取。
 *
 * <p>主流程：创建 {@code PENDING} 岗位 -> 解析时改为 {@code PARSING} -> 保存画像并改为
 * {@code PENDING_CONFIRM} -> 用户确认后改为 {@code CONFIRMED} 且保持待审核 -> 管理员审核通过后才公开。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PositionService {

    private static final String REDIS_CACHE_PREFIX = "position:parse:";
    private static final long CACHE_TTL_SECONDS = 7 * 24 * 60 * 60;

    private final PositionRepository positionRepository;
    private final PositionProfileRepository positionProfileRepository;
    private final JdFileStorageService fileStorageService;
    private final JdTextExtractor textExtractor;
    private final JdAnalysisAgent jdAnalysisAgent;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    @Value("${position.upload.max-size:10485760}")
    private long maxFileSize;

    /**
     * 根据用户粘贴的 JD 创建岗位并立即进入解析流程。
     *
     * <p>先保存不可公开的 {@code PENDING} 岗位，再直接调用解析方法。当前调用会沿本次请求继续执行解析，
     * 因此返回的解析状态取决于解析结束后的真实状态；审核状态始终先保持 {@code PENDING}。
     */
    @Transactional
    public PositionCreateResponse createPosition(Long userId, PositionCreateRequest request) {
        // 1. 先校验创建画像所需的岗位名称和 JD 正文。
        if (request.getPositionName() == null || request.getPositionName().isBlank()) {
            throw new BusinessException(POSITION_NAME_EMPTY, "岗位名称不能为空");
        }
        if (request.getJdContent() == null || request.getJdContent().isBlank()) {
            throw new BusinessException(JD_CONTENT_EMPTY, "JD 描述不能为空");
        }

        // 2. 创建私有且待审核的岗位记录，公开范围留给管理员审核决定。
        Position position = new Position();
        position.setUserId(userId);
        position.setPositionName(request.getPositionName().trim());
        position.setCompanyName(request.getCompanyName());
        position.setLocation(request.getLocation());
        position.setSalaryRange(request.getSalaryRange());
        position.setJobCategory(request.getJobCategory());
        position.setLevel(request.getLevel());
        position.setJdContent(request.getJdContent().trim());
        position.setParseStatus(PositionParseStatus.PENDING);
        position.setAuditStatus(PositionAuditStatus.PENDING);
        position.setIsPublic(false);
        positionRepository.save(position);

        // 3. 当前是本类直接调用，@Async 代理不会介入，解析会继续占用本次调用线程。
        parsePositionAsync(position.getId(), userId);

        return new PositionCreateResponse(
                position.getId(), position.getPositionName(),
                position.getParseStatus().name(), position.getAuditStatus().name());
    }

    /**
     * 上传 JD 文件、提取正文并创建岗位。
     *
     * <p>文件落盘后，从本次上传内容提取正文并保存岗位，再进入与粘贴文本相同的解析流程。
     * 当前岗位记录不保存已落盘文件的路径，后续也无法通过岗位记录定位并清理该文件。
     */
    @Transactional
    public PositionCreateResponse uploadPosition(Long userId, MultipartFile file, String fileType, String positionName) {
        // 1. 校验文件和岗位名称，再进行落盘与文本提取。
        validateFile(file, fileType);
        if (positionName == null || positionName.isBlank()) {
            throw new BusinessException(POSITION_NAME_EMPTY, "岗位名称不能为空");
        }

        // 文件系统和数据库不共享事务；后续提取或保存失败时，已落盘文件不会自动删除。
        String filePath = fileStorageService.store(userId, file);
        String jdContent = textExtractor.extract(file, fileType.toUpperCase());

        // 2. 当前只保存提取出的 JD 正文，filePath 没有写入岗位记录。
        Position position = new Position();
        position.setUserId(userId);
        position.setPositionName(positionName.trim());
        position.setJdContent(jdContent);
        position.setJobCategory(inferJobCategory(jdContent));
        position.setParseStatus(PositionParseStatus.PENDING);
        position.setAuditStatus(PositionAuditStatus.PENDING);
        position.setIsPublic(false);
        positionRepository.save(position);

        // 3. 直接进入画像解析；当前调用不会经过 Spring 异步代理。
        parsePositionAsync(position.getId(), userId);

        return new PositionCreateResponse(
                position.getId(), position.getPositionName(),
                position.getParseStatus().name(), position.getAuditStatus().name());
    }

    /**
     * 从岗位记录中的 JD 正文生成并保存岗位画像。
     *
     * <p>当前由本类的创建、上传和重新解析方法直接调用，虽然方法带有 {@link Async}，实际不会经过 Spring 异步代理。
     * 处理顺序为：状态改为 {@code PARSING} -> 按正文摘要读取缓存或调用岗位分析 Agent ->
     * 覆盖或新建画像 -> 状态改为 {@code PENDING_CONFIRM}。
     *
     * <p>调用方负责保证岗位属于当前用户且状态允许解析；本方法自身只按 {@code positionId} 加载记录，
     * 不会再次核对 {@code userId}、锁定状态或解析状态。传入的 {@code userId} 仅在首次创建画像记录时使用。
     * 缓存键只包含 JD 正文摘要，不包含用户标识和岗位大类，因此相同 JD 会跨用户、跨岗位大类复用七天缓存。
     *
     * <p>正文为空时状态改为 {@code PARSE_FAILED}。模型调用失败、模型结果为空或 JSON 无法解析时，
     * {@link JdAnalysisAgent} 会返回按岗位大类生成的兜底画像；当前流程仍将其保存并进入 {@code PENDING_CONFIRM}。
     * 缓存访问或画像保存出现的其他异常不会在这里改为失败状态，而是继续向调用方抛出。
     */
    @Async
    public void parsePositionAsync(Long positionId, Long userId) {
        // 1. 加载待解析记录并标记为解析中。
        Position position = positionRepository.findById(positionId).orElse(null);
        if (position == null) {
            log.warn("[PositionService] 解析岗位时记录不存在: positionId={}", positionId);
            return;
        }
        position.setParseStatus(PositionParseStatus.PARSING);
        positionRepository.save(position);

        // 2. JD 正文为空时直接失败，不调用岗位分析 Agent。
        String jdText = position.getJdContent();
        if (jdText == null || jdText.isBlank()) {
            log.warn("[PositionService] JD 内容为空: positionId={}", positionId);
            position.setParseStatus(PositionParseStatus.PARSE_FAILED);
            positionRepository.save(position);
            return;
        }

        // 3. 全局按 JD 摘要复用七天缓存；缓存不区分用户和岗位大类，未命中时再调用岗位分析 Agent。
        String md5 = md5(jdText);
        String cacheKey = REDIS_CACHE_PREFIX + md5;
        String cached = redisTemplate.opsForValue().get(cacheKey);
        PositionProfileData profileData;
        if (cached != null) {
            log.info("[PositionService] 岗位解析缓存命中: positionId={}", positionId);
            try {
                profileData = objectMapper.readValue(cached, PositionProfileData.class);
            } catch (JsonProcessingException e) {
                log.warn("[PositionService] 缓存解析失败，重新调用 LLM: positionId={}", positionId, e);
                // 当前只绕过坏缓存，本次生成的新画像不会覆盖它；相同 JD 下次仍会再次命中该坏值。
                profileData = jdAnalysisAgent.analyze(jdText, position.getJobCategory());
            }
        } else {
            profileData = jdAnalysisAgent.analyze(jdText, position.getJobCategory());
            try {
                // 缓存未命中时会缓存 Agent 的原样返回，包括模型失败时产生的兜底画像。
                redisTemplate.opsForValue().set(cacheKey, objectMapper.writeValueAsString(profileData),
                        java.time.Duration.ofSeconds(CACHE_TTL_SECONDS));
            } catch (JsonProcessingException e) {
                log.warn("[PositionService] 缓存写入失败: positionId={}", positionId, e);
            }
        }

        // 4. Agent 的兜底画像也是正常返回值，当前仍会保存并交给用户确认。
        saveProfile(positionId, userId, profileData);
        position.setParseStatus(PositionParseStatus.PENDING_CONFIRM);
        if (profileData.getBasicInfo() != null && profileData.getBasicInfo().getLevel() != null) {
            position.setLevel(profileData.getBasicInfo().getLevel());
        }
        positionRepository.save(position);
    }

    /**
     * 查询岗位列表（当前用户上传的岗位）。
     *
     * <p>解析状态或审核状态为空时不使用对应条件；传入无法识别的状态值也会被当作未指定条件，
     * 当前不会向调用方返回参数错误。
     */
    @Transactional(readOnly = true)
    public PositionListResponse listPositions(Long userId, int page, int size,
                                              String parseStatus, String auditStatus) {
        Pageable pageable = PageRequest.of(page, size);
        PositionParseStatus parseStatusEnum = parseEnum(parseStatus, PositionParseStatus.class);
        PositionAuditStatus auditStatusEnum = parseEnum(auditStatus, PositionAuditStatus.class);

        Page<Position> positionPage;
        if (parseStatusEnum != null && auditStatusEnum != null) {
            positionPage = positionRepository.findByUserIdAndParseStatusAndAuditStatus(
                    userId, parseStatusEnum, auditStatusEnum, pageable);
        } else if (parseStatusEnum != null) {
            positionPage = positionRepository.findByUserIdAndParseStatus(userId, parseStatusEnum, pageable);
        } else if (auditStatusEnum != null) {
            positionPage = positionRepository.findByUserIdAndAuditStatus(userId, auditStatusEnum, pageable);
        } else {
            positionPage = positionRepository.findByUserId(userId, pageable);
        }

        PositionListResponse response = new PositionListResponse();
        response.setContent(positionPage.getContent().stream().map(this::toListItem).toList());
        response.setTotalElements(positionPage.getTotalElements());
        response.setTotalPages(positionPage.getTotalPages());
        response.setCurrentPage(positionPage.getNumber());
        return response;
    }

    /**
     * 查询所有已审核通过的公共岗位列表，供其他用户选择。
     */
    @Transactional(readOnly = true)
    public PositionListResponse listPublicPositions(int page, int size) {
        Pageable pageable = PageRequest.of(page, size);
        Page<Position> positionPage = positionRepository.findByIsPublicTrueAndAuditStatus(
                PositionAuditStatus.APPROVED, pageable);

        PositionListResponse response = new PositionListResponse();
        response.setContent(positionPage.getContent().stream().map(this::toListItem).toList());
        response.setTotalElements(positionPage.getTotalElements());
        response.setTotalPages(positionPage.getTotalPages());
        response.setCurrentPage(positionPage.getNumber());
        return response;
    }

    /**
     * 查询当前用户可访问的岗位列表（用户自己的岗位 + 已审核通过的公共岗位）。
     */
    @Transactional(readOnly = true)
    public PositionListResponse listAccessiblePositions(Long userId, int page, int size) {
        Pageable pageable = PageRequest.of(page, size);
        Page<Position> positionPage = positionRepository.findAccessibleByUserId(
                userId, PositionAuditStatus.APPROVED, pageable);

        PositionListResponse response = new PositionListResponse();
        response.setContent(positionPage.getContent().stream().map(this::toListItem).toList());
        response.setTotalElements(positionPage.getTotalElements());
        response.setTotalPages(positionPage.getTotalPages());
        response.setCurrentPage(positionPage.getNumber());
        return response;
    }

    /**
     * 管理员：分页查询所有用户上传的岗位（支持按审核状态筛选）。
     *
     * <p>本方法自身不检查管理员角色，权限依赖管理端 Controller 的方法安全配置。审核状态为空或非法时，
     * 当前会查询全部岗位而不是返回参数错误。
     */
    @Transactional(readOnly = true)
    public PositionListResponse listAllPositions(int page, int size, String auditStatus) {
        Pageable pageable = PageRequest.of(page, size);
        PositionAuditStatus auditStatusEnum = parseEnum(auditStatus, PositionAuditStatus.class);

        Page<Position> positionPage;
        if (auditStatusEnum != null) {
            positionPage = positionRepository.findByAuditStatus(auditStatusEnum, pageable);
        } else {
            positionPage = positionRepository.findAll(pageable);
        }

        PositionListResponse response = new PositionListResponse();
        response.setContent(positionPage.getContent().stream().map(this::toListItem).toList());
        response.setTotalElements(positionPage.getTotalElements());
        response.setTotalPages(positionPage.getTotalPages());
        response.setCurrentPage(positionPage.getNumber());
        return response;
    }

    /**
     * 将接口传入的状态筛选值转换为枚举。
     *
     * <p>空值或非法值都返回 {@code null}；列表查询把 {@code null} 解释为不使用该筛选条件。
     */
    private <E extends Enum<E>> E parseEnum(String value, Class<E> enumClass) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Enum.valueOf(enumClass, value.toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * 查询岗位详情（用户视角：仅可查看自己的岗位或已审核通过的公共岗位）。
     */
    @Transactional(readOnly = true)
    public PositionDetailResponse getPositionDetail(Long userId, Long positionId) {
        Position position = positionRepository.findById(positionId)
                .orElseThrow(() -> new BusinessException(POSITION_NOT_FOUND, "岗位不存在"));
        if (!position.getUserId().equals(userId)
                && !(Boolean.TRUE.equals(position.getIsPublic())
                        && position.getAuditStatus() == PositionAuditStatus.APPROVED)) {
            throw new BusinessException(POSITION_ACCESS_DENIED, "无权查看该岗位");
        }
        return toDetailResponse(position);
    }

    /**
     * 管理员：查询任意岗位详情。
     *
     * <p>本方法只按岗位标识查询，不校验岗位归属或管理员角色；管理员权限由上层管理端 Controller 保证。
     */
    @Transactional(readOnly = true)
    public PositionDetailResponse getPositionDetailForAdmin(Long positionId) {
        Position position = positionRepository.findById(positionId)
                .orElseThrow(() -> new BusinessException(POSITION_NOT_FOUND, "岗位不存在"));
        return toDetailResponse(position);
    }

    /**
<<<<<<< ours
     * 查询岗位画像。
     *
     * <p>这里只允许岗位所有者查询，其他用户即使能够查看已审核通过的公共岗位详情，也不能通过本方法读取其画像。
     * 画像尚未生成时返回空画像；已保存的画像 JSON 无法解析时同样降级为空画像，而不是抛出解析异常。
=======
     * 查询岗位画像（用户视角：仅可查看自己的岗位或已审核通过的公共岗位）。
>>>>>>> theirs
     */
    @Transactional(readOnly = true)
    public PositionProfileResponse getPositionProfile(Long userId, Long positionId) {
        Position position = positionRepository.findById(positionId)
                .orElseThrow(() -> new BusinessException(POSITION_NOT_FOUND, "岗位不存在"));
        if (!position.getUserId().equals(userId)
                && !(Boolean.TRUE.equals(position.getIsPublic())
                        && position.getAuditStatus() == PositionAuditStatus.APPROVED)) {
            throw new BusinessException(POSITION_ACCESS_DENIED, "无权查看该岗位画像");
        }
        PositionProfileResponse response = new PositionProfileResponse();
        response.setPositionId(positionId);
        response.setParseStatus(position.getParseStatus().name());

        PositionProfile profile = positionProfileRepository.findByPositionId(positionId).orElse(null);
        if (profile == null) {
            log.info("[PositionService] 岗位画像暂未生成: positionId={}", positionId);
            response.setProfile(PositionProfileData.empty());
            return response;
        }
        response.setProfileId(profile.getId());
        response.setProfile(parseProfileJson(profile.getProfileData()));
        return response;
    }

    /**
     * 用户确认岗位画像。
     *
     * <p>仅 {@code PENDING_CONFIRM} 状态允许确认。用户提交的画像会覆盖当前画像记录，岗位改为
     * {@code CONFIRMED}；同时重新进入待审核且保持私有，只有管理员审核通过后才可供其他用户选择。
     */
    @Transactional
    public void confirmPosition(Long userId, Long positionId, PositionProfileData profileData) {
        // 1. 校验岗位归属、当前状态和用户提交的画像。
        Position position = findPositionByIdAndUserId(positionId, userId);
        if (position.getParseStatus() != PositionParseStatus.PENDING_CONFIRM) {
            throw new BusinessException(POSITION_STATUS_INVALID, "当前状态不允许确认");
        }
        if (profileData == null) {
            throw new BusinessException(PROFILE_DATA_INVALID, "画像数据无效");
        }

        // 2. 更新画像和岗位状态；saveProfile 会复用已有画像记录而不是新增一条。
        saveProfile(positionId, userId, profileData);
        position.setParseStatus(PositionParseStatus.CONFIRMED);
        // 用户确认后进入待审核状态，由管理员审核通过后方可变为公共岗位
        position.setAuditStatus(PositionAuditStatus.PENDING);
        position.setIsPublic(false);
        if (profileData.getBasicInfo() != null && profileData.getBasicInfo().getLevel() != null) {
            position.setLevel(profileData.getBasicInfo().getLevel());
        }
        positionRepository.save(position);
    }

    /**
     * 使用已保存的 JD 正文重新执行岗位解析。
     *
     * <p>已锁定或正在解析的岗位不能重试。当前实现只把状态重置为 {@code PENDING} 后再次调用解析，
     * 不会清除按正文摘要保存的缓存，因此相同 JD 可能继续复用原画像。
     */
    @Transactional
    public PositionCreateResponse reparsePosition(Long userId, Long positionId) {
        Position position = findPositionByIdAndUserId(positionId, userId);
        if (position.isLocked()) {
            throw new BusinessException(POSITION_STATUS_INVALID, "岗位已锁定，不可重新解析");
        }
        if (position.getParseStatus() == PositionParseStatus.PARSING) {
            throw new BusinessException(POSITION_STATUS_INVALID, "岗位正在解析中，请稍后再试");
        }

        // 仅重置状态并重新进入原解析流程；当前不会清除或绕过画像缓存。
        position.setParseStatus(PositionParseStatus.PENDING);
        positionRepository.save(position);
        parsePositionAsync(position.getId(), userId);

        return new PositionCreateResponse(
                position.getId(), position.getPositionName(),
                position.getParseStatus().name(), position.getAuditStatus().name());
    }

    /**
     * 删除未被面试占用的岗位及画像。
     *
     * <p>岗位记录没有保存上传文件路径，因此这里不会删除之前落盘的 JD 文件。
     */
    @Transactional
    public void deletePosition(Long userId, Long positionId) {
        Position position = findPositionByIdAndUserId(positionId, userId);
        if (position.isLocked()) {
            throw new BusinessException(POSITION_STATUS_INVALID, "岗位已锁定，不可删除");
        }
        positionProfileRepository.findByPositionId(positionId).ifPresent(positionProfileRepository::delete);
        positionRepository.delete(position);
    }

    /**
     * 管理员审核岗位。
     *
     * <p>管理员角色由接口层的权限配置保证，本方法自身不做角色校验。只有当前审核状态为
     * {@code PENDING} 的岗位可进入此流程；目标值按枚举直接解析，因此也允许再次设置为 {@code PENDING}。
     * 审核结果会同时更新公开标记、审核备注、审核人和审核时间，只有 {@code APPROVED} 会将岗位公开。
     */
    @Transactional
    public void auditPosition(Long positionId, AuditPositionRequest request, Long auditorId) {
        Position position = positionRepository.findById(positionId)
                .orElseThrow(() -> new BusinessException(POSITION_NOT_FOUND, "岗位不存在"));
        if (position.getAuditStatus() != PositionAuditStatus.PENDING) {
            throw new BusinessException(POSITION_NOT_AUDITABLE, "只有待审核状态可审核");
        }

        PositionAuditStatus targetStatus;
        try {
            targetStatus = PositionAuditStatus.valueOf(request.getStatus().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(PROFILE_DATA_INVALID, "审核状态无效");
        }

        position.setAuditStatus(targetStatus);
        // 审核通过后岗位变为公共岗位，可被其他用户选择；拒绝后保持私有
        position.setIsPublic(targetStatus == PositionAuditStatus.APPROVED);
        position.setAuditRemark(request.getRemark());
        position.setAuditorId(auditorId);
        position.setAuditedAt(LocalDateTime.now());
        positionRepository.save(position);
    }

    private void validateFile(MultipartFile file, String fileType) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(FILE_READ_FAILED, "文件为空");
        }
        if (file.getSize() > maxFileSize) {
            throw new BusinessException(FILE_SIZE_EXCEEDED, "文件大小超过10MB限制");
        }
        String type = fileType == null ? "" : fileType.toUpperCase();
        if (!type.equals("PDF") && !type.equals("TXT")) {
            throw new BusinessException(FILE_TYPE_NOT_SUPPORTED, "仅支持 PDF 和 TXT 格式");
        }
    }

    private Position findPositionByIdAndUserId(Long positionId, Long userId) {
        return positionRepository.findByIdAndUserId(positionId, userId)
                .orElseThrow(() -> new BusinessException(POSITION_NOT_FOUND, "岗位不存在"));
    }

    /**
     * 保存一个岗位当前唯一的画像。
     *
     * <p>画像先序列化为 JSON；同一 {@code positionId} 已有记录时覆盖原数据，首次保存时才写入传入的
     * {@code userId}。序列化失败会转为业务异常，不会写入部分画像数据。
     */
    private void saveProfile(Long positionId, Long userId, PositionProfileData data) {
        String json;
        try {
            json = objectMapper.writeValueAsString(data);
        } catch (JsonProcessingException e) {
            throw new BusinessException(PROFILE_DATA_INVALID, "画像数据序列化失败", e);
        }

        // 同一个岗位只维护一条画像：首次解析时新建，重新解析或用户确认时覆盖原记录。
        PositionProfile profile = positionProfileRepository.findByPositionId(positionId)
                .orElseGet(() -> {
                    PositionProfile p = new PositionProfile();
                    p.setPositionId(positionId);
                    p.setUserId(userId);
                    return p;
                });
        profile.setProfileData(json);
        positionProfileRepository.save(profile);
    }

    /**
     * 将仓储中的岗位画像 JSON 还原为对象，并兼容 H2 可能产生的双层 JSON 字符串。
     *
     * <p>空值或最终解析失败都降级为空画像，不把存量数据格式问题继续抛给查询接口。
     */
    private PositionProfileData parseProfileJson(String json) {
        if (json == null || json.isBlank()) {
            return PositionProfileData.empty();
        }
        String normalized = json.trim();
        if (normalized.startsWith("\"") && normalized.endsWith("\"")) {
            try {
                normalized = objectMapper.readValue(normalized, String.class);
            } catch (JsonProcessingException e) {
                log.warn("[PositionService] profile_data 字符串字面量解码失败，尝试直接解析", e);
            }
        }
        try {
            return objectMapper.readValue(normalized, PositionProfileData.class);
        } catch (JsonProcessingException e) {
            log.error("[PositionService] 画像 JSON 解析失败", e);
            return PositionProfileData.empty();
        }
    }

    private PositionListItemResponse toListItem(Position position) {
        PositionListItemResponse item = new PositionListItemResponse();
        item.setPositionId(position.getId());
        item.setPositionName(position.getPositionName());
        item.setCompanyName(position.getCompanyName());
        item.setJobCategory(position.getJobCategory());
        item.setLevel(position.getLevel());
        item.setJdContent(position.getJdContent());
        item.setParseStatus(position.getParseStatus().name());
        item.setAuditStatus(position.getAuditStatus().name());
        item.setIsPublic(position.getIsPublic());
        item.setUserId(position.getUserId());
        item.setCreatedAt(position.getCreatedAt());
        item.setUpdatedAt(position.getUpdatedAt());
        return item;
    }

    private PositionDetailResponse toDetailResponse(Position position) {
        PositionDetailResponse response = new PositionDetailResponse();
        response.setPositionId(position.getId());
        response.setPositionName(position.getPositionName());
        response.setCompanyName(position.getCompanyName());
        response.setLocation(position.getLocation());
        response.setSalaryRange(position.getSalaryRange());
        response.setJobCategory(position.getJobCategory());
        response.setLevel(position.getLevel());
        response.setJdContent(position.getJdContent());
        response.setParseStatus(position.getParseStatus().name());
        response.setAuditStatus(position.getAuditStatus().name());
        response.setIsPublic(position.getIsPublic());
        response.setUserId(position.getUserId());
        response.setCreatedAt(position.getCreatedAt());
        response.setUpdatedAt(position.getUpdatedAt());
        response.setAuditedAt(position.getAuditedAt());
        return response;
    }

    /**
     * 根据上传文件提取出的 JD 正文推断岗位大类。
     *
     * <p>当前只在文件上传流程使用，按关键词识别技术、产品、设计和运营类；正文为空或没有命中关键词时
     * 返回 {@code TECH}。粘贴文本创建岗位时直接使用请求中的岗位大类，不调用本方法。
     */
    private String inferJobCategory(String jdText) {
        if (jdText == null) {
            return "TECH";
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

    private String md5(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            return String.valueOf(input.hashCode());
        }
    }
}
