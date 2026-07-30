package com.interviewcoach.resume.application.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.interviewcoach.common.exception.BusinessException;
import com.interviewcoach.resume.application.event.ResumeAiQuotaReservation;
import com.interviewcoach.resume.application.event.ResumeParseRequestedEvent;
import com.interviewcoach.resume.domain.agent.ResumeAnalysisAgent;
import com.interviewcoach.resume.domain.model.UserProfileData;
import com.interviewcoach.resume.infrastructure.cache.ResumeParseCacheProperties;
import com.interviewcoach.resume.infrastructure.parser.ResumeTextExtractor;
import com.interviewcoach.resume.infrastructure.redis.ResumeAiQuotaService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 执行文件提取、可选缓存和模型事实提取；数据库写入由状态服务使用短事务完成。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ResumeParseWorker {

    private static final String REDIS_CACHE_PREFIX = "resume:parse:";

    private final ResumeParseStateService stateService;
    private final ResumeTextExtractor textExtractor;
    private final ResumeAnalysisAgent resumeAnalysisAgent;
    private final ResumeProfileNormalizer normalizer;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final ResumeParseCacheProperties cacheProperties;
    private final ResumeAiQuotaService quotaService;

    /**
     * 依次执行状态认领、文本提取、模型解析和草稿写回；异常后按数据库确认的终态结算 quota。
     */
    public void parse(ResumeParseRequestedEvent event) {
        Instant startedAt = Instant.now();
        String stage = "STATE_START";
        ResumeAiQuotaReservation quotaReservation = event.quotaReservation();
        try {
            if (event.taskLease() == null) {
                throw new IllegalStateException("AI task lease is required before worker execution");
            }
            ResumeParseStateService.ParseInput input = stateService.start(
                    event.resumeId(), event.userId(), event.generation());
            if (input == null) {
                ResumeAiTaskOutcome outcome = confirmFailure(
                        event,
                        quotaReservation,
                        "STATE_START_FAILED",
                        "解析任务状态已变化，请稍后重试",
                        stage);
                settleQuota(event, quotaReservation, outcome, stage);
                return;
            }
            quotaReservation = input.quotaReservation();

            stage = "TEXT_EXTRACTION";
            String resumeText = textExtractor.extractFromFile(input.filePath(), input.fileType());
            if (resumeText == null || resumeText.isBlank()) {
                throw new IllegalArgumentException("简历解析内容为空");
            }

            stage = "PROFILE_RESOLUTION";
            UserProfileData profileData = resolveProfile(
                    resumeText,
                    event,
                    () -> markAttemptStarted(event.userId(), input.quotaReservation()));

            stage = "RESULT_PERSISTENCE";
            boolean completed = event.taskLease().executeIfValid(() -> stateService.complete(
                    event.resumeId(), event.userId(), event.generation(), profileData));
            if (!completed) {
                ResumeAiTaskOutcome outcome = confirmFailure(
                        event,
                        quotaReservation,
                        "RESULT_PERSISTENCE_FAILED",
                        "简历解析结果未写入，请稍后重试",
                        stage);
                settleQuota(event, quotaReservation, outcome, stage);
                return;
            }
            settleQuota(
                    event,
                    quotaReservation,
                    ResumeAiTaskOutcome.SUCCESS_CONFIRMED,
                    stage);
            log.info("[ResumeParse] 后台解析结束: resumeId={}, generation={}, durationMs={}",
                    event.resumeId(), event.generation(),
                    Duration.between(startedAt, Instant.now()).toMillis());
        } catch (Exception e) {
            log.error("[ResumeParse] 后台解析失败: resumeId={}, generation={}, stage={}, errorType={}",
                    event.resumeId(), event.generation(), stage, e.getClass().getSimpleName());
            ResumeAiTaskOutcome outcome = confirmFailure(
                    event,
                    quotaReservation,
                    stage + "_FAILED",
                    safeErrorMessage(stage, e),
                    stage);
            settleQuota(event, quotaReservation, outcome, stage);
        }
    }

    /**
     * 调度失败发生在事务提交之后，因此通过独立短事务把当前代次标记为可手动重试。
     */
    public void markSchedulingFailed(ResumeParseRequestedEvent event) {
        settleAfterFailure(
                event,
                "SCHEDULING_FAILED",
                "解析任务调度失败，请稍后重试",
                "SCHEDULING");
    }

    /**
     * 准入失败时只失败当前代次，任务不会进入虚拟线程。
     */
    public void markAdmissionFailed(ResumeParseRequestedEvent event) {
        settleAfterFailure(
                event,
                "ADMISSION_REJECTED",
                "解析任务并发已达上限或基础设施不可用，请稍后重试",
                "ADMISSION");
    }

    private UserProfileData resolveProfile(
            String resumeText,
            ResumeParseRequestedEvent event,
            Runnable beforeModelCall) {
        String cacheKey = buildCacheKey(event.userId(), resumeText);
        if (cacheProperties.isEnabled()
                && !event.forceRefresh()
                && event.quotaReservation() == null) {
            UserProfileData cached = readCache(cacheKey, event.resumeId());
            if (cached != null) {
                return normalizer.normalize(cached);
            }
        }

        UserProfileData profileData = normalizer.normalize(
                resumeAnalysisAgent.analyze(resumeText, beforeModelCall));
        if (cacheProperties.isEnabled()) {
            writeCache(cacheKey, profileData, event.resumeId());
        }
        return profileData;
    }

    private void markAttemptStarted(
            Long userId, ResumeAiQuotaReservation quotaReservation) {
        if (quotaReservation != null
                && !quotaService.markAttemptStarted(userId, quotaReservation)) {
            throw new IllegalStateException("Model-call start transition was rejected");
        }
    }

    private void settleAfterFailure(
            ResumeParseRequestedEvent event,
            String errorCode,
            String errorMessage,
            String stage) {
        ResumeAiQuotaReservation reservation = event.quotaReservation();
        ResumeAiTaskOutcome outcome = confirmFailure(
                event, reservation, errorCode, errorMessage, stage);
        settleQuota(event, reservation, outcome, stage);
    }

    private ResumeAiTaskOutcome confirmFailure(
            ResumeParseRequestedEvent event,
            ResumeAiQuotaReservation reservation,
            String errorCode,
            String errorMessage,
            String stage) {
        try {
            return stateService.fail(
                    event.resumeId(),
                    event.userId(),
                    event.generation(),
                    reservation,
                    errorCode,
                    errorMessage);
        } catch (RuntimeException confirmationError) {
            log.warn(
                    "[ResumeParse] 无法确认数据库任务终态: resumeId={}, generation={}, stage={}, errorType={}",
                    event.resumeId(),
                    event.generation(),
                    stage,
                    confirmationError.getClass().getSimpleName());
            return ResumeAiTaskOutcome.UNKNOWN;
        }
    }

    private void settleQuota(
            ResumeParseRequestedEvent event,
            ResumeAiQuotaReservation reservation,
            ResumeAiTaskOutcome outcome,
            String stage) {
        try {
            boolean settled = ResumeAiQuotaSettlement.settle(
                    quotaService,
                    event.userId(),
                    reservation,
                    outcome,
                    () -> stateService.clearQuotaReservation(
                            event.resumeId(),
                            event.userId(),
                            event.generation(),
                            reservation));
            if (outcome == ResumeAiTaskOutcome.UNKNOWN || !settled) {
                log.warn(
                        "[ResumeParse] 额度结算待恢复: resumeId={}, generation={}, stage={}, outcome={}",
                        event.resumeId(),
                        event.generation(),
                        stage,
                        outcome);
            }
        } catch (RuntimeException settlementError) {
            log.warn(
                    "[ResumeParse] 额度结算失败并保留恢复凭据: resumeId={}, generation={}, "
                            + "stage={}, outcome={}, errorType={}",
                    event.resumeId(),
                    event.generation(),
                    stage,
                    outcome,
                    settlementError.getClass().getSimpleName());
        }
    }

    private UserProfileData readCache(String cacheKey, Long resumeId) {
        try {
            String cached = redisTemplate.opsForValue().get(cacheKey);
            return cached == null ? null : objectMapper.readValue(cached, UserProfileData.class);
        } catch (JsonProcessingException e) {
            log.warn("[ResumeParse] 缓存内容无效: resumeId={}, errorType={}",
                    resumeId, e.getClass().getSimpleName());
            deleteCacheQuietly(cacheKey, resumeId);
            return null;
        } catch (RuntimeException e) {
            log.warn("[ResumeParse] 缓存读取失败，降级调用模型: resumeId={}, errorType={}",
                    resumeId, e.getClass().getSimpleName());
            return null;
        }
    }

    private void writeCache(String cacheKey, UserProfileData profileData, Long resumeId) {
        try {
            redisTemplate.opsForValue().set(
                    cacheKey, objectMapper.writeValueAsString(profileData), cacheProperties.getTtl());
        } catch (RuntimeException | JsonProcessingException e) {
            log.warn("[ResumeParse] 缓存写入失败，不影响画像草稿: resumeId={}, errorType={}",
                    resumeId, e.getClass().getSimpleName());
        }
    }

    private void deleteCacheQuietly(String cacheKey, Long resumeId) {
        try {
            redisTemplate.delete(cacheKey);
        } catch (RuntimeException e) {
            log.warn("[ResumeParse] 坏缓存删除失败，仍降级调用模型: resumeId={}, errorType={}",
                    resumeId, e.getClass().getSimpleName());
        }
    }

    /**
     * Key 同时隔离用户、Prompt 和事实 Schema；正文只进入 SHA-256，不写入 Key 明文。
     */
    private String buildCacheKey(Long userId, String resumeText) {
        return REDIS_CACHE_PREFIX + "u" + userId
                + ":p" + safeVersion(ResumeAnalysisAgent.PROMPT_VERSION)
                + ":s" + ResumeProfileSupport.PROFILE_SCHEMA_VERSION
                + ":" + sha256(resumeText);
    }

    private String safeVersion(String value) {
        return value == null ? "unknown" : value.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private String sha256(String text) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 算法不可用", e);
        }
    }

    private String safeErrorMessage(String stage, Exception error) {
        if ("TEXT_EXTRACTION".equals(stage)) {
            return "简历文本提取失败";
        }
        if ("PROFILE_RESOLUTION".equals(stage)) {
            return "简历事实分析失败";
        }
        if (error instanceof BusinessException businessException) {
            return businessException.getMessage();
        }
        return "简历解析失败，请重试";
    }
}
