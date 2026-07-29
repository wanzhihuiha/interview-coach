package com.interviewcoach.resume.application.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.interviewcoach.resume.application.event.ResumeParseRequestedEvent;
import com.interviewcoach.resume.domain.agent.ResumeAnalysisAgent;
import com.interviewcoach.resume.domain.model.UserProfileData;
import com.interviewcoach.resume.infrastructure.parser.ResumeTextExtractor;
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
 * 简历解析后台工作器，在专用线程池中执行文件提取、缓存访问和 LLM 分析。
 *
 * <p>外部资源调用均在数据库事务之外执行；只有任务抢占和最终结果写入通过
 * {@link ResumeParseStateService} 使用短事务完成。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ResumeParseWorker {

    private static final String REDIS_CACHE_PREFIX = "resume:parse:";
    private static final Duration CACHE_TTL = Duration.ofDays(7);

    private final ResumeParseStateService stateService;
    private final ResumeTextExtractor textExtractor;
    private final ResumeAnalysisAgent resumeAnalysisAgent;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    /**
     * 执行一次解析任务。
     * 重复或过期事件会在状态锁定阶段被忽略；任何未处理异常都会记录失败阶段，
     * 并尝试把仍处于等待或解析中的任务标记为失败。
     */
    public void parse(ResumeParseRequestedEvent event) {
        Instant startedAt = Instant.now();
        String stage = "STATE_START";
        try {
            ResumeParseStateService.ParseInput input = stateService.start(event.resumeId(), event.userId());
            if (input == null) {
                return;
            }

            log.info("[ResumeParse] 后台解析开始: resumeId={}, forceRefresh={}",
                    event.resumeId(), event.forceRefresh());

            stage = "TEXT_EXTRACTION";
            Instant extractionStartedAt = Instant.now();
            log.info("[ResumeParse] 文本提取开始: resumeId={}, fileType={}",
                    event.resumeId(), input.fileType());
            String resumeText = textExtractor.extractFromFile(input.filePath(), input.fileType());
            log.info("[ResumeParse] 文本提取完成: resumeId={}, textLength={}, durationMs={}",
                    event.resumeId(), resumeText.length(),
                    Duration.between(extractionStartedAt, Instant.now()).toMillis());
            if (resumeText.isBlank()) {
                throw new IllegalArgumentException("简历解析内容为空");
            }

            stage = "PROFILE_RESOLUTION";
            UserProfileData profileData = resolveProfile(resumeText, event.forceRefresh(), event.resumeId());

            stage = "RESULT_PERSISTENCE";
            boolean completed = stateService.complete(event.resumeId(), event.userId(), profileData);
            if (completed) {
                log.info("[ResumeParse] 后台解析完成: resumeId={}, status={}, durationMs={}",
                        event.resumeId(), "PENDING_CONFIRM",
                        Duration.between(startedAt, Instant.now()).toMillis());
            }
        } catch (Exception e) {
            log.error("[ResumeParse] 后台解析失败: resumeId={}, stage={}, errorType={}, durationMs={}",
                    event.resumeId(), stage, e.getClass().getSimpleName(),
                    Duration.between(startedAt, Instant.now()).toMillis(), e);
            try {
                stateService.fail(event.resumeId(), event.userId());
            } catch (Exception stateException) {
                log.error("[ResumeParse] 记录解析失败状态异常: resumeId={}, originalStage={}, errorType={}",
                        event.resumeId(), stage, stateException.getClass().getSimpleName(), stateException);
            }
        }
    }

    /**
     * 在线程池拒绝任务时记录可重试的失败状态。
     */
    public void markSchedulingFailed(ResumeParseRequestedEvent event) {
        stateService.fail(event.resumeId(), event.userId());
    }

    /**
     * 从缓存或 LLM 获取画像。
     * 缓存按提取文本摘要跨用户复用且保留 7 天；强制刷新只绕过读取，仍会回写同一内容键。
     */
    private UserProfileData resolveProfile(String resumeText, boolean forceRefresh, Long resumeId) {
        String cacheKey = REDIS_CACHE_PREFIX + md5(resumeText);
        if (!forceRefresh) {
            UserProfileData cachedProfile = readCache(cacheKey, resumeId);
            if (cachedProfile != null) {
                log.info("[ResumeParse] 简历解析缓存命中: resumeId={}", resumeId);
                return cachedProfile;
            }
        } else {
            log.info("[ResumeParse] 重新解析绕过旧缓存: resumeId={}", resumeId);
        }

        log.info("[ResumeParse] 缓存未命中，开始 LLM 分析: resumeId={}", resumeId);
        Instant llmStartedAt = Instant.now();
        UserProfileData profileData = resumeAnalysisAgent.analyze(resumeText);
        log.info("[ResumeParse] LLM 分析返回: resumeId={}, durationMs={}",
                resumeId, Duration.between(llmStartedAt, Instant.now()).toMillis());
        writeCache(cacheKey, profileData, resumeId);
        return profileData;
    }

    /**
     * 读取画像缓存；坏 JSON 或 Redis 异常会降级为缓存未命中并继续调用 LLM。
     */
    private UserProfileData readCache(String cacheKey, Long resumeId) {
        try {
            String cached = redisTemplate.opsForValue().get(cacheKey);
            if (cached == null) {
                return null;
            }
            return objectMapper.readValue(cached, UserProfileData.class);
        } catch (JsonProcessingException e) {
            log.warn("[ResumeParse] 缓存内容无效，重新调用 LLM: resumeId={}, errorType={}",
                    resumeId, e.getClass().getSimpleName());
            return null;
        } catch (RuntimeException e) {
            log.warn("[ResumeParse] 缓存读取失败，降级调用 LLM: resumeId={}, errorType={}",
                    resumeId, e.getClass().getSimpleName());
            return null;
        }
    }

    /**
     * 尽力写入画像缓存；序列化或 Redis 写入失败不会阻断画像持久化。
     */
    private void writeCache(String cacheKey, UserProfileData profileData, Long resumeId) {
        try {
            redisTemplate.opsForValue().set(cacheKey, objectMapper.writeValueAsString(profileData), CACHE_TTL);
            log.debug("[ResumeParse] 画像缓存写入完成: resumeId={}, ttlDays={}",
                    resumeId, CACHE_TTL.toDays());
        } catch (JsonProcessingException e) {
            log.warn("[ResumeParse] 缓存序列化失败，不影响画像保存: resumeId={}, errorType={}",
                    resumeId, e.getClass().getSimpleName());
        } catch (RuntimeException e) {
            log.warn("[ResumeParse] 缓存写入失败，不影响画像保存: resumeId={}, errorType={}",
                    resumeId, e.getClass().getSimpleName());
        }
    }

    /**
     * 生成缓存内容指纹，仅用于定位相同文本，不承担密码学安全或文件完整性校验职责。
     */
    private String md5(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 算法不可用", e);
        }
    }
}
