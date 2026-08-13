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
 * 消费已携带任务许可的解析事件，在事务外完成文件读取、缓存查找和模型事实提取。
 * 数据库认领与结果写回委托状态服务使用短事务完成，结束时再按可确认的数据库终态结算 Redis 额度。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ResumeParseWorker {

    /**
     * 事实画像缓存 Key 的固定命名空间；后续还拼接用户、Prompt、Schema 和正文摘要以隔离语义版本。
     * 前缀本身不设独立配置，改变会使现有缓存自然失去命中；精确命名依据缺失。
     */
    private static final String REDIS_CACHE_PREFIX = "resume:parse:";

    /** 在数据库短事务中认领任务、写回草稿并维护额度恢复凭据。 */
    private final ResumeParseStateService stateService;
    /** 按持久化路径和文件类型读取 PDF 或 TXT 正文的文件适配器。 */
    private final ResumeTextExtractor textExtractor;
    /** 对脱敏正文调用模型并解析为可核对事实的 Agent。 */
    private final ResumeAnalysisAgent resumeAnalysisAgent;
    /** 统一技能、集合和经验等级派生规则的规范化组件。 */
    private final ResumeProfileNormalizer normalizer;
    /** 读取、写入和删除事实画像 JSON 缓存的 Redis 客户端。 */
    private final StringRedisTemplate redisTemplate;
    /** 在缓存 JSON 与事实画像对象之间转换的项目 ObjectMapper。 */
    private final ObjectMapper objectMapper;
    /** 控制解析缓存是否启用及其 TTL 的配置。 */
    private final ResumeParseCacheProperties cacheProperties;
    /** 在真正进入模型调用前标记额度已开始使用，并在结束时结算。 */
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
            // 先锁定并认领当前 PENDING 代次；过期或已被处理的事件返回 null，不进入文件和模型阶段。
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
            // 文件提取发生在数据库事务外；读取失败交给统一失败终态和额度结算路径。
            String resumeText = textExtractor.extractFromFile(input.filePath(), input.fileType());
            if (resumeText == null || resumeText.isBlank()) {
                throw new IllegalArgumentException("简历解析内容为空");
            }

            stage = "PROFILE_RESOLUTION";
            // 先尝试符合条件的 Redis 缓存；真正调用模型前回调会把额度从预留切为已开始。
            UserProfileData profileData = resolveProfile(
                    resumeText,
                    event,
                    () -> markAttemptStarted(event.userId(), input.quotaReservation()));

            stage = "RESULT_PERSISTENCE";
            // 只有许可仍有效时才用短事务写草稿；续期丢失可阻止旧执行者继续落库。
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
            // 数据库确认成功后处理额度；同日 Redis 转换成功或额度日期关闭后才清理恢复凭据。
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
        // 事件已登记但未进入执行器，仍需写失败终态并处理可能存在的手动额度。
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
        // 后台准入失败时任务尚未读取文件，按同一终态协议失败并结算额度。
        settleAfterFailure(
                event,
                "ADMISSION_REJECTED",
                "解析任务并发已达上限或基础设施不可用，请稍后重试",
                "ADMISSION");
    }

    /**
     * 在缓存可用且任务允许复用时返回规范化缓存，否则调用事实 Agent 并回填缓存。
     * 手动强制刷新或携带额度预留的任务不读缓存；缓存故障降级到模型，不阻断主流程。
     */
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
                // 缓存仍经过当前规范化规则，避免旧对象中的空集合或技能别名直接进入草稿。
                return normalizer.normalize(cached);
            }
        }

        // Agent 在 LLM 调用紧前执行 beforeModelCall；回调失败时不会向外部模型发送正文。
        UserProfileData profileData = normalizer.normalize(
                resumeAnalysisAgent.analyze(resumeText, beforeModelCall));
        if (cacheProperties.isEnabled()) {
            // 缓存写入是可选加速，失败只记录并继续返回已取得的事实画像。
            writeCache(cacheKey, profileData, event.resumeId());
        }
        return profileData;
    }

    /** 在外部模型调用前把手动任务额度原子标记为已开始；状态不匹配时拒绝继续调用。 */
    private void markAttemptStarted(
            Long userId, ResumeAiQuotaReservation quotaReservation) {
        if (quotaReservation != null
                && !quotaService.markAttemptStarted(userId, quotaReservation)) {
            throw new IllegalStateException("Model-call start transition was rejected");
        }
    }

    /** 为调度或准入失败复用“数据库终态确认后再结算额度”的顺序。 */
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

    /** 尝试写入当前代次失败状态；数据库异常或任务凭据不匹配时返回 UNKNOWN。 */
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

    /**
     * 根据已确认终态调用统一结算器，并在同日 Redis 转换成功或额度日期关闭时按任务凭据清理数据库字段。
     */
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

    /** 读取并反序列化画像缓存；坏 JSON 尝试删除后回源，Redis 故障直接回源。 */
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

    /** 按配置 TTL 写入画像 JSON；TTL 的精确取值依据由缓存配置项说明。 */
    private void writeCache(String cacheKey, UserProfileData profileData, Long resumeId) {
        try {
            redisTemplate.opsForValue().set(
                    cacheKey, objectMapper.writeValueAsString(profileData), cacheProperties.getTtl());
        } catch (RuntimeException | JsonProcessingException e) {
            log.warn("[ResumeParse] 缓存写入失败，不影响画像草稿: resumeId={}, errorType={}",
                    resumeId, e.getClass().getSimpleName());
        }
    }

    /** 尽力删除无法反序列化的缓存；删除失败仍允许当前请求调用模型重建。 */
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

    /** 将版本值限制为适合放入 Redis Key 的字符；空版本使用 unknown 隔离。 */
    private String safeVersion(String value) {
        return value == null ? "unknown" : value.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    /** 将 UTF-8 正文计算为 SHA-256 十六进制摘要，避免正文原文进入 Redis Key。 */
    private String sha256(String text) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 算法不可用", e);
        }
    }

    /** 将内部异常按执行阶段收敛为可持久化的安全提示，不保存文件内容或模型原文。 */
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
