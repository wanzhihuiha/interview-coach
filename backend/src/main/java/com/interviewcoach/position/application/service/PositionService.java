package com.interviewcoach.position.application.service;

import static com.interviewcoach.position.application.service.PositionErrorCode.*;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.interviewcoach.common.domain.JobCategoryType;
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
import com.interviewcoach.position.domain.entity.PositionLevel;
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
 * 岗位应用服务，处理 JD 上传、解析、确认与管理。
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
     * 创建岗位（粘贴文本）。
     */
    @Transactional
    public PositionCreateResponse createPosition(Long userId, PositionCreateRequest request) {
        if (request.getPositionName() == null || request.getPositionName().isBlank()) {
            throw new BusinessException(POSITION_NAME_EMPTY, "岗位名称不能为空");
        }
        if (request.getJdContent() == null || request.getJdContent().isBlank()) {
            throw new BusinessException(JD_CONTENT_EMPTY, "JD 描述不能为空");
        }

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

        parsePositionAsync(position.getId(), userId);

        return new PositionCreateResponse(
                position.getId(), position.getPositionName(),
                position.getParseStatus().name(), position.getParseStatus().getDisplayName(),
                position.getAuditStatus().name(), position.getAuditStatus().getDisplayName());
    }

    /**
     * 上传 JD 文件。
     */
    @Transactional
    public PositionCreateResponse uploadPosition(Long userId, MultipartFile file, String fileType, String positionName) {
        validateFile(file, fileType);
        if (positionName == null || positionName.isBlank()) {
            throw new BusinessException(POSITION_NAME_EMPTY, "岗位名称不能为空");
        }

        String filePath = fileStorageService.store(userId, file);
        String jdContent = textExtractor.extract(file, fileType.toUpperCase());

        Position position = new Position();
        position.setUserId(userId);
        position.setPositionName(positionName.trim());
        position.setJdContent(jdContent);
        position.setJobCategory(inferJobCategory(jdContent));
        position.setParseStatus(PositionParseStatus.PENDING);
        position.setAuditStatus(PositionAuditStatus.PENDING);
        position.setIsPublic(false);
        positionRepository.save(position);

        parsePositionAsync(position.getId(), userId);

        return new PositionCreateResponse(
                position.getId(), position.getPositionName(),
                position.getParseStatus().name(), position.getParseStatus().getDisplayName(),
                position.getAuditStatus().name(), position.getAuditStatus().getDisplayName());
    }

    /**
     * 异步解析岗位 JD。
     */
    @Async
    public void parsePositionAsync(Long positionId, Long userId) {
        Position position = positionRepository.findById(positionId).orElse(null);
        if (position == null) {
            log.warn("[PositionService] 异步解析时岗位不存在: positionId={}", positionId);
            return;
        }
        position.setParseStatus(PositionParseStatus.PARSING);
        positionRepository.save(position);

        String jdText = position.getJdContent();
        if (jdText == null || jdText.isBlank()) {
            log.warn("[PositionService] JD 内容为空: positionId={}", positionId);
            position.setParseStatus(PositionParseStatus.PARSE_FAILED);
            positionRepository.save(position);
            return;
        }

        // 缓存检查
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
                profileData = jdAnalysisAgent.analyze(jdText, position.getJobCategory());
            }
        } else {
            profileData = jdAnalysisAgent.analyze(jdText, position.getJobCategory());
            try {
                redisTemplate.opsForValue().set(cacheKey, objectMapper.writeValueAsString(profileData),
                        java.time.Duration.ofSeconds(CACHE_TTL_SECONDS));
            } catch (JsonProcessingException e) {
                log.warn("[PositionService] 缓存写入失败: positionId={}", positionId, e);
            }
        }

        saveProfile(positionId, userId, profileData);
        position.setParseStatus(PositionParseStatus.PENDING_CONFIRM);
        if (profileData.getBasicInfo() != null && profileData.getBasicInfo().getLevel() != null) {
            position.setLevel(profileData.getBasicInfo().getLevel());
        }
        positionRepository.save(position);
    }

    /**
     * 查询岗位列表（当前用户上传的岗位）。
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
     */
    @Transactional(readOnly = true)
    public PositionDetailResponse getPositionDetailForAdmin(Long positionId) {
        Position position = positionRepository.findById(positionId)
                .orElseThrow(() -> new BusinessException(POSITION_NOT_FOUND, "岗位不存在"));
        return toDetailResponse(position);
    }

    /**
     * 查询岗位画像（用户视角：仅可查看自己的岗位或已审核通过的公共岗位）。
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
        response.setParseStatusLabel(position.getParseStatus().getDisplayName());

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
     * 用户确认岗位解析结果。
     */
    @Transactional
    public void confirmPosition(Long userId, Long positionId, PositionProfileData profileData) {
        Position position = findPositionByIdAndUserId(positionId, userId);
        if (position.getParseStatus() != PositionParseStatus.PENDING_CONFIRM) {
            throw new BusinessException(POSITION_STATUS_INVALID, "当前状态不允许确认");
        }
        if (profileData == null) {
            throw new BusinessException(PROFILE_DATA_INVALID, "画像数据无效");
        }

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
     * 重新解析岗位 JD。
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

        position.setParseStatus(PositionParseStatus.PENDING);
        positionRepository.save(position);
        parsePositionAsync(position.getId(), userId);

        return new PositionCreateResponse(
                position.getId(), position.getPositionName(),
                position.getParseStatus().name(), position.getParseStatus().getDisplayName(),
                position.getAuditStatus().name(), position.getAuditStatus().getDisplayName());
    }

    /**
     * 删除岗位。
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

    private void saveProfile(Long positionId, Long userId, PositionProfileData data) {
        String json;
        try {
            json = objectMapper.writeValueAsString(data);
        } catch (JsonProcessingException e) {
            throw new BusinessException(PROFILE_DATA_INVALID, "画像数据序列化失败", e);
        }

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
        item.setJobCategoryLabel(JobCategoryType.displayNameOf(position.getJobCategory()));
        item.setLevel(position.getLevel());
        item.setLevelLabel(PositionLevel.displayNameOf(position.getLevel()));
        item.setJdContent(position.getJdContent());
        item.setParseStatus(position.getParseStatus().name());
        item.setParseStatusLabel(position.getParseStatus().getDisplayName());
        item.setAuditStatus(position.getAuditStatus().name());
        item.setAuditStatusLabel(position.getAuditStatus().getDisplayName());
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
        response.setJobCategoryLabel(JobCategoryType.displayNameOf(position.getJobCategory()));
        response.setLevel(position.getLevel());
        response.setLevelLabel(PositionLevel.displayNameOf(position.getLevel()));
        response.setJdContent(position.getJdContent());
        response.setParseStatus(position.getParseStatus().name());
        response.setParseStatusLabel(position.getParseStatus().getDisplayName());
        response.setAuditStatus(position.getAuditStatus().name());
        response.setAuditStatusLabel(position.getAuditStatus().getDisplayName());
        response.setIsPublic(position.getIsPublic());
        response.setUserId(position.getUserId());
        response.setCreatedAt(position.getCreatedAt());
        response.setUpdatedAt(position.getUpdatedAt());
        response.setAuditedAt(position.getAuditedAt());
        return response;
    }

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
