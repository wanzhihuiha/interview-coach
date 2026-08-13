package com.interviewcoach.user.application.service;

import com.interviewcoach.common.exception.BusinessException;
import com.interviewcoach.user.application.dto.ConsentStatusResponse;
import com.interviewcoach.user.application.dto.ConsentSubmitRequest;
import com.interviewcoach.user.domain.entity.ConsentType;
import com.interviewcoach.user.domain.entity.UserConsentRecord;
import com.interviewcoach.user.domain.repository.UserConsentRecordRepository;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 用户协议同意状态查询与记录追加的应用服务。
 *
 * <p>用户同意接口调用本服务查询或提交协议状态，简历 AI 处理入口也调用本服务做服务端前置校验。
 * 本服务通过同意记录仓储读取历史记录，并将每次提交按类型追加为新记录，不覆盖既有记录。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConsentService {

    /**
     * 客户端未提供有效文本时写入新同意记录的当前固定版本 {@code 1.0}。
     * 该默认值的版本治理依据缺失，目前没有可靠项目文档说明其演进规则；调整会改变后续新增记录的版本标签。
     */
    private static final String DEFAULT_CONSENT_VERSION = "1.0";

    /**
     * 负责查询历史同意状态并追加保存同意记录的 JPA 仓储。
     */
    private final UserConsentRecordRepository consentRecordRepository;

    /**
     * 查询指定用户是否分别存在 LLM 服务条款和隐私政策同意记录。
     *
     * @param userId 安全上下文提供的当前用户主键
     * @return 两类协议各自的存在性状态
     */
    @Transactional(readOnly = true)
    public ConsentStatusResponse getConsentStatus(Long userId) {
        // 分别查询两种协议的历史记录；存在性判断当前不比较同意版本。
        boolean llmService = consentRecordRepository.hasConsented(userId, ConsentType.LLM_SERVICE);
        boolean privacyPolicy = consentRecordRepository.hasConsented(userId, ConsentType.PRIVACY_POLICY);
        return new ConsentStatusResponse(llmService, privacyPolicy);
    }

    /**
     * 为当前用户提交的每个不同协议类型追加一条同意记录。
     *
     * <p>版本为空白时使用固定默认值；记录同时保存按当前请求头优先级提取的 IP 文本和 User-Agent。
     * 任一类型解析或保存失败会回滚本方法中的数据库写入。</p>
     *
     * @param userId 安全上下文提供的当前用户主键
     * @param request 前端提交的类型码和可选版本
     * @param httpRequest 当前 HTTP 请求，用于提取审计元数据；可为 {@code null}
     */
    @Transactional
    public void submitConsents(Long userId, ConsentSubmitRequest request, HttpServletRequest httpRequest) {
        // 在服务端收口空版本回退，确保本次新增记录都使用同一个版本文本。
        String version = StringUtils.hasText(request.getVersion()) ? request.getVersion() : DEFAULT_CONSENT_VERSION;
        // 按当前请求头优先级提取审计 IP，并直接读取 User-Agent；两者都可能为空。
        String ipAddress = extractClientIp(httpRequest);
        String userAgent = httpRequest != null ? httpRequest.getHeader("User-Agent") : null;

        // 将前端稳定码解析为枚举并去重，避免一次请求为同一类型追加重复记录。
        List<ConsentType> types = request.getConsentTypes().stream()
                .map(this::parseConsentType)
                .distinct()
                .collect(Collectors.toList());

        for (ConsentType type : types) {
            UserConsentRecord record = new UserConsentRecord();
            record.setUserId(userId);
            record.setConsentType(type);
            record.setConsentVersion(version);
            record.setIpAddress(ipAddress);
            record.setUserAgent(userAgent);
            // 每个类型保存为独立历史记录，后续存在性查询不会覆盖或更新旧版本记录。
            consentRecordRepository.save(record);
            log.info("[ConsentService] 用户同意记录已保存: userId={}, type={}, version={}", userId, type, version);
        }
    }

    /**
     * 判断指定用户是否存在任意版本的 LLM 服务条款同意记录。
     *
     * @param userId 待检查用户主键
     * @return 存在记录时为 {@code true}，否则为 {@code false}
     */
    @Transactional(readOnly = true)
    public boolean hasConsentedLlmService(Long userId) {
        // 仓储按用户和类型查询最新记录，但存在性结果不比较协议版本。
        return consentRecordRepository.hasConsented(userId, ConsentType.LLM_SERVICE);
    }

    /**
     * 在调用外部模型前同时校验 LLM 服务和隐私政策同意，避免只依赖前端弹窗。
     *
     * <p>简历上传、确认、重新解析和分析重试等 AI 处理入口调用本方法；任一类型没有历史记录时抛出业务异常，
     * 调用方不会继续提交后续 AI 处理步骤。</p>
     *
     * @param userId 待执行 AI 处理的所属用户主键
     */
    @Transactional(readOnly = true)
    public void requireAiProcessingConsent(Long userId) {
        // 分别读取两类同意记录，只有两项都存在才允许后续 AI 处理继续。
        boolean llmService = consentRecordRepository.hasConsented(userId, ConsentType.LLM_SERVICE);
        boolean privacyPolicy = consentRecordRepository.hasConsented(userId, ConsentType.PRIVACY_POLICY);
        if (!llmService || !privacyPolicy) {
            throw new BusinessException(UserErrorCode.CONSENT_REQUIRED, "请先同意 LLM 服务条款和隐私政策");
        }
    }

    /**
     * 将前端类型码按不区分大小写的当前行为解析为同意枚举。
     *
     * <p>未知文本转换为业务异常；{@code null} 元素当前会在大小写转换时抛出空指针异常。</p>
     */
    private ConsentType parseConsentType(String value) {
        try {
            return ConsentType.valueOf(value.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(UserErrorCode.CONSENT_TYPE_INVALID, "同意类型无效: " + value);
        }
    }

    /**
     * 按固定请求头顺序提取用于同意记录审计的 IP 文本。
     *
     * <p>方法取首个非空且非 {@code unknown} 的头值，并在逗号分隔时保留第一段；均无值时使用远端地址。
     * 当前实现不验证代理来源是否可信，因此该值只表示请求携带或容器提供的审计文本。</p>
     */
    private String extractClientIp(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        String[] headers = {"X-Forwarded-For", "Proxy-Client-IP", "WL-Proxy-Client-IP", "X-Real-IP"};
        for (String header : headers) {
            String ip = request.getHeader(header);
            if (StringUtils.hasText(ip) && !"unknown".equalsIgnoreCase(ip)) {
                return ip.split(",")[0].trim();
            }
        }
        return request.getRemoteAddr();
    }
}
