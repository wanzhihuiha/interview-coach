package com.interviewcoach.resume.application.service;

import static com.interviewcoach.resume.application.service.ResumeErrorCode.*;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.interviewcoach.common.exception.BusinessException;
import com.interviewcoach.resume.domain.agent.ResumeAnalysisAgent;
import com.interviewcoach.resume.domain.entity.Resume;
import com.interviewcoach.resume.domain.entity.ResumeParseStatus;
import com.interviewcoach.resume.domain.entity.ResumeProfile;
import com.interviewcoach.resume.domain.model.UserProfileData;
import com.interviewcoach.resume.domain.repository.ResumeProfileRepository;
import com.interviewcoach.resume.domain.repository.ResumeRepository;
import com.interviewcoach.resume.infrastructure.parser.ResumeTextExtractor;
import com.interviewcoach.resume.infrastructure.storage.FileStorageService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
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
 * 简历准备流程的应用入口。
 *
 * <p>上游由简历接口调用；本服务负责保存简历文件和记录、组织文本解析与画像生成、保存画像以及处理用户确认。
 * 文本提取交给 {@link ResumeTextExtractor}，画像生成和经验等级判断交给 {@link ResumeAnalysisAgent}，
 * 最终结果写入简历及画像仓储，供后续创建面试时读取。
 *
 * <p>主流程：上传文件并创建 {@code PENDING} 记录 -> 解析时改为 {@code PARSING} ->
 * 保存画像并改为 {@code PENDING_CONFIRM} -> 用户确认后改为 {@code CONFIRMED}。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ResumeService {

    private static final String REDIS_CACHE_PREFIX = "resume:parse:";
    private static final long CACHE_TTL_SECONDS = 7 * 24 * 60 * 60;

    private final ResumeRepository resumeRepository;
    private final ResumeProfileRepository resumeProfileRepository;
    private final FileStorageService fileStorageService;
    private final ResumeTextExtractor textExtractor;
    private final ResumeAnalysisAgent resumeAnalysisAgent;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    @Value("${resume.upload.max-size:10485760}")
    private long maxFileSize;

    /**
     * 上传简历并立即进入解析流程。
     *
     * <p>先校验和保存文件，再创建 {@code PENDING} 简历记录，随后直接调用解析方法。
     * 当前调用会沿本次请求继续执行解析，因此返回的状态取决于解析结束后的真实状态，而不一定仍是 {@code PENDING}。
     *
     * @return 已创建简历的标识、名称和当前解析状态
     */
    @Transactional
    public com.interviewcoach.resume.application.dto.ResumeUploadResponse uploadResume(Long userId, MultipartFile file, String fileType) {
        // 1. 校验文件类型和大小，避免不支持的文件进入存储与解析流程。
        validateFile(file, fileType);

        // 文件系统和数据库不共享事务；后续保存失败时，已落盘文件不会随数据库事务自动回滚。
        String filePath = fileStorageService.store(userId, file);

        // 2. 先建立 PENDING 记录，使后续画像可以通过 resumeId 关联到本次上传。
        Resume resume = new Resume();
        resume.setUserId(userId);
        resume.setResumeName(file.getOriginalFilename());
        resume.setFilePath(filePath);
        resume.setFileType(fileType.toUpperCase());
        resume.setFileSize(file.getSize());
        resume.setParseStatus(ResumeParseStatus.PENDING);
        resumeRepository.save(resume);

        // 3. 当前是本类直接调用，@Async 代理不会介入，解析会继续占用本次调用线程。
        parseResumeAsync(resume.getId(), userId);

        return new com.interviewcoach.resume.application.dto.ResumeUploadResponse(
                resume.getId(), resume.getResumeName(), resume.getParseStatus().name(), 0);
    }

    /**
     * 从已存储文件解析简历并保存画像。
     *
     * <p>当前由本类的上传和重新解析方法直接调用，虽然方法带有 {@link Async}，实际不会经过 Spring 异步代理。
     * 处理顺序为：状态改为 {@code PARSING} -> 提取文本 -> 按文本摘要读取缓存或调用画像分析 ->
     * 覆盖或新建画像 -> 状态改为 {@code PENDING_CONFIRM}。
     *
     * <p>调用方负责保证简历属于当前用户且状态允许解析；本方法自身只按 {@code resumeId} 加载记录，
     * 不会再次核对 {@code userId}、锁定状态或解析状态。传入的 {@code userId} 仅在首次创建画像记录时使用。
     * 缓存键只包含原始简历文本的摘要，不包含用户标识，因此内容完全相同的简历会跨用户复用七天缓存。
     *
     * <p>文件提取失败或文本为空时状态改为 {@code PARSE_FAILED}。模型调用或模型返回解析失败时，
     * {@link ResumeAnalysisAgent} 会返回空画像；当前流程仍会保存该画像并进入 {@code PENDING_CONFIRM}。
     * 缓存访问、文本脱敏或画像保存出现的其他异常不会在这里改为失败状态，而是继续向调用方抛出。
     */
    @Async
    public void parseResumeAsync(Long resumeId, Long userId) {
        // 1. 加载待解析记录并标记为解析中。
        Resume resume = resumeRepository.findById(resumeId).orElse(null);
        if (resume == null) {
            log.warn("[ResumeService] 解析简历时记录不存在: resumeId={}", resumeId);
            return;
        }
        resume.setParseStatus(ResumeParseStatus.PARSING);
        resumeRepository.save(resume);

        // 2. 从上传文件提取纯文本；这里失败时不再继续调用画像分析。
        String resumeText;
        try {
            resumeText = textExtractor.extractFromFile(resume.getFilePath(), resume.getFileType());
        } catch (Exception e) {
            log.error("[ResumeService] 简历文本提取失败: resumeId={}", resumeId, e);
            resume.setParseStatus(ResumeParseStatus.PARSE_FAILED);
            resumeRepository.save(resume);
            return;
        }

        if (resumeText.isBlank()) {
            log.warn("[ResumeService] 简历解析内容为空: resumeId={}", resumeId);
            resume.setParseStatus(ResumeParseStatus.PARSE_FAILED);
            resumeRepository.save(resume);
            return;
        }

        // 3. 全局按原始文本摘要复用七天缓存；缓存不按用户隔离，未命中时再调用简历分析 Agent。
        String md5 = md5(resumeText);
        String cacheKey = REDIS_CACHE_PREFIX + md5;
        String cached = redisTemplate.opsForValue().get(cacheKey);
        UserProfileData profileData;
        if (cached != null) {
            log.info("[ResumeService] 简历解析缓存命中: resumeId={}", resumeId);
            try {
                profileData = objectMapper.readValue(cached, UserProfileData.class);
            } catch (JsonProcessingException e) {
                log.warn("[ResumeService] 缓存解析失败，重新调用 LLM: resumeId={}", resumeId, e);
                // 当前只绕过坏缓存，本次生成的新画像不会覆盖它；相同文本下次仍会再次命中该坏值。
                profileData = resumeAnalysisAgent.analyze(resumeText);
            }
        } else {
            profileData = resumeAnalysisAgent.analyze(resumeText);
            try {
                // 缓存未命中时会缓存 Agent 的原样返回，包括模型失败时产生的空画像。
                redisTemplate.opsForValue().set(cacheKey, objectMapper.writeValueAsString(profileData),
                        java.time.Duration.ofSeconds(CACHE_TTL_SECONDS));
            } catch (JsonProcessingException e) {
                log.warn("[ResumeService] 缓存写入失败: resumeId={}", resumeId, e);
            }
        }

        // 4. Agent 的空画像也是正常返回值，当前仍会保存并交给用户确认。
        saveProfile(resumeId, userId, profileData);
        resume.setParseStatus(ResumeParseStatus.PENDING_CONFIRM);
        resume.setJobCategory(inferJobCategory(profileData));
        resumeRepository.save(resume);
    }

    /**
     * 查询简历列表。
     */
    @Transactional(readOnly = true)
    public com.interviewcoach.resume.application.dto.ResumeListResponse listResumes(Long userId, int page, int size) {
        Pageable pageable = PageRequest.of(page, size);
        Page<Resume> resumePage = resumeRepository.findByUserId(userId, pageable);

        com.interviewcoach.resume.application.dto.ResumeListResponse response = new com.interviewcoach.resume.application.dto.ResumeListResponse();
        response.setContent(resumePage.getContent().stream().map(this::toListItem).toList());
        response.setTotalElements(resumePage.getTotalElements());
        response.setTotalPages(resumePage.getTotalPages());
        response.setCurrentPage(resumePage.getNumber());
        return response;
    }

    /**
     * 查询简历详情。
     *
     * <p>先按用户标识校验简历归属。画像记录不存在时 {@code parsedData} 保持为空；画像记录存在但 JSON
     * 无法解析时，{@code parsedData} 返回空画像。{@code confirmedAt} 没有独立字段，当前直接使用
     * {@code CONFIRMED} 状态简历的最后更新时间，因此确认后的其他更新也可能改变这个时间。
     */
    @Transactional(readOnly = true)
    public com.interviewcoach.resume.application.dto.ResumeDetailResponse getResumeDetail(Long userId, Long resumeId) {
        Resume resume = findResumeByIdAndUserId(resumeId, userId);
        com.interviewcoach.resume.application.dto.ResumeDetailResponse response = new com.interviewcoach.resume.application.dto.ResumeDetailResponse();
        response.setResumeId(resume.getId());
        response.setFileName(resume.getResumeName());
        response.setFileType(resume.getFileType());
        response.setFileSize(resume.getFileSize());
        response.setStatus(resume.getParseStatus().name());
        response.setJobCategory(resume.getJobCategory());
        response.setCreatedAt(resume.getCreatedAt());
        response.setConfirmedAt(resume.getParseStatus() == ResumeParseStatus.CONFIRMED ? resume.getUpdatedAt() : null);

        resumeProfileRepository.findByResumeId(resumeId)
                .ifPresent(profile -> response.setParsedData(parseProfileJson(profile.getProfileData())));
        return response;
    }

    /**
     * 查询简历画像。
     *
     * <p>仅简历所有者可查询。简历或画像记录不存在时直接报错；已保存的画像 JSON 无法解析时不报错，
     * 而是返回结构稳定的空画像。
     */
    @Transactional(readOnly = true)
    public com.interviewcoach.resume.application.dto.ResumeProfileResponse getResumeProfile(Long userId, Long resumeId) {
        Resume resume = findResumeByIdAndUserId(resumeId, userId);
        ResumeProfile profile = resumeProfileRepository.findByResumeId(resumeId)
                .orElseThrow(() -> new BusinessException(RESUME_NOT_FOUND, "简历画像不存在"));

        com.interviewcoach.resume.application.dto.ResumeProfileResponse response = new com.interviewcoach.resume.application.dto.ResumeProfileResponse();
        response.setProfileId(profile.getId());
        response.setResumeId(profile.getResumeId());
        response.setProfile(parseProfileJson(profile.getProfileData()));
        response.setExperienceLevel(profile.getExperienceLevel());
        response.setStatus(resume.getParseStatus().name());
        return response;
    }

    /**
     * 用户确认简历画像。
     *
     * <p>仅 {@code PENDING_CONFIRM} 状态允许确认。用户提交的画像会覆盖当前画像记录，
     * 同时重新推断岗位大类并将简历改为 {@code CONFIRMED}，供创建面试时选择。
     */
    @Transactional
    public void confirmResume(Long userId, Long resumeId, UserProfileData profileData) {
        // 1. 校验简历归属、当前状态和用户提交的画像。
        Resume resume = findResumeByIdAndUserId(resumeId, userId);
        if (resume.getParseStatus() != ResumeParseStatus.PENDING_CONFIRM) {
            throw new BusinessException(RESUME_STATUS_INVALID, "当前状态不允许确认");
        }
        if (profileData == null) {
            throw new BusinessException(PROFILE_DATA_INVALID, "画像数据无效");
        }

        // 2. 更新画像和简历状态；saveProfile 会复用已有画像记录而不是新增一条。
        saveProfile(resumeId, userId, profileData);
        resume.setParseStatus(ResumeParseStatus.CONFIRMED);
        resume.setJobCategory(inferJobCategory(profileData));
        resumeRepository.save(resume);
    }

    /**
     * 使用原文件重新执行简历解析。
     *
     * <p>已锁定或正在解析的简历不能重试。当前实现只把状态重置为 {@code PENDING} 后再次调用解析，
     * 不会清除按文本摘要保存的缓存，因此相同文件可能继续复用原画像。
     */
    @Transactional
    public com.interviewcoach.resume.application.dto.ResumeUploadResponse reparseResume(Long userId, Long resumeId) {
        Resume resume = findResumeByIdAndUserId(resumeId, userId);
        if (resume.isLocked()) {
            throw new BusinessException(RESUME_LOCKED, "简历已锁定，不可重新解析");
        }
        if (resume.getParseStatus() == ResumeParseStatus.PARSING) {
            throw new BusinessException(RESUME_PARSING_IN_PROGRESS, "简历正在解析中，请稍后再试");
        }

        // 仅重置状态并重新进入原解析流程；当前不会清除或绕过画像缓存。
        resume.setParseStatus(ResumeParseStatus.PENDING);
        resumeRepository.save(resume);
        parseResumeAsync(resume.getId(), userId);

        return new com.interviewcoach.resume.application.dto.ResumeUploadResponse(
                resume.getId(), resume.getResumeName(), resume.getParseStatus().name(), 0);
    }

    /**
     * 删除未被面试占用的简历和画像，并尝试删除物理文件。
     *
     * <p>物理文件先于数据库记录处理，但两者不共享事务。文件删除失败时底层只记录日志，数据库删除仍会继续；
     * 文件删除成功后若数据库操作失败，文件也不会自动恢复。</p>
     */
    @Transactional
    public void deleteResume(Long userId, Long resumeId) {
        Resume resume = findResumeByIdAndUserId(resumeId, userId);
        if (resume.isLocked()) {
            throw new BusinessException(RESUME_LOCKED_FOR_DELETE, "简历已锁定，不可删除");
        }
        fileStorageService.delete(resume.getFilePath());
        resumeProfileRepository.findByResumeId(resumeId).ifPresent(resumeProfileRepository::delete);
        resumeRepository.delete(resume);
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

    private Resume findResumeByIdAndUserId(Long resumeId, Long userId) {
        return resumeRepository.findByIdAndUserId(resumeId, userId)
                .orElseThrow(() -> new BusinessException(RESUME_NOT_FOUND, "简历不存在"));
    }

    /**
     * 保存一份简历当前唯一的画像，并同步重算画像中的经验等级。
     *
     * <p>画像先序列化为 JSON；同一 {@code resumeId} 已有记录时覆盖原数据，首次保存时才写入传入的
     * {@code userId}。经验等级由 {@link ResumeAnalysisAgent} 根据本次画像重新推断。序列化失败会转为业务异常，
     * 不会写入部分画像数据。
     */
    private void saveProfile(Long resumeId, Long userId, UserProfileData data) {
        String json;
        try {
            json = objectMapper.writeValueAsString(data);
        } catch (JsonProcessingException e) {
            throw new BusinessException(PROFILE_DATA_INVALID, "画像数据序列化失败", e);
        }

        // 同一份简历只维护一条画像：首次解析时新建，重新解析或用户确认时覆盖原记录。
        ResumeProfile profile = resumeProfileRepository.findByResumeId(resumeId)
                .orElseGet(() -> {
                    ResumeProfile p = new ResumeProfile();
                    p.setResumeId(resumeId);
                    p.setUserId(userId);
                    return p;
                });
        profile.setProfileData(json);
        profile.setExperienceLevel(resumeAnalysisAgent.inferExperienceLevel(data));
        resumeProfileRepository.save(profile);
    }

    /**
     * 将仓储中的画像 JSON 还原为对象，并兼容 H2 可能产生的双层 JSON 字符串。
     *
     * <p>空值或最终解析失败都降级为空画像，不把存量数据格式问题继续抛给查询接口。
     */
    private UserProfileData parseProfileJson(String json) {
        if (json == null || json.isBlank()) {
            return UserProfileData.empty();
        }
        String normalized = json.trim();
        // H2 JSON 列可能把对象存成了 JSON 字符串字面量，需要二次解析
        if (normalized.startsWith("\"") && normalized.endsWith("\"")) {
            try {
                normalized = objectMapper.readValue(normalized, String.class);
            } catch (JsonProcessingException e) {
                log.warn("[ResumeService] profile_data 字符串字面量解码失败，尝试直接解析", e);
            }
        }
        try {
            return objectMapper.readValue(normalized, UserProfileData.class);
        } catch (JsonProcessingException e) {
            log.error("[ResumeService] 画像 JSON 解析失败", e);
            return UserProfileData.empty();
        }
    }

    private com.interviewcoach.resume.application.dto.ResumeListItemResponse toListItem(Resume resume) {
        com.interviewcoach.resume.application.dto.ResumeListItemResponse item = new com.interviewcoach.resume.application.dto.ResumeListItemResponse();
        item.setResumeId(resume.getId());
        item.setFileName(resume.getResumeName());
        item.setStatus(resume.getParseStatus().name());
        item.setJobCategory(resume.getJobCategory());
        item.setCreatedAt(resume.getCreatedAt());
        item.setUpdatedAt(resume.getUpdatedAt());
        return item;
    }

    /**
     * 根据画像技能标签推断后续面试使用的岗位大类。
     *
     * <p>当前只识别技术、产品和设计类关键词；画像或技能标签为空、或没有命中任何关键词时均返回
     * {@code TECH}，不会推断出运营等其他类别。
     */
    private String inferJobCategory(UserProfileData data) {
        if (data == null || data.getSkillTags() == null) {
            return "TECH";
        }
        String text = String.join(" ", data.getSkillTags()).toLowerCase();
        if (text.contains("java") || text.contains("python") || text.contains("前端")
                || text.contains("后端") || text.contains("测试") || text.contains("运维")) {
            return "TECH";
        }
        if (text.contains("产品") || text.contains("需求") || text.contains("prd")) {
            return "PRODUCT";
        }
        if (text.contains("设计") || text.contains("ui") || text.contains("ux")) {
            return "DESIGN";
        }
        return "TECH";
    }

    private String md5(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("MD5 算法不可用", e);
        }
    }
}
