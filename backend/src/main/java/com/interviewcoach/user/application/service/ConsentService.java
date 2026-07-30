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
 * 用户同意管理服务，处理 LLM 服务条款与隐私政策的同意记录。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConsentService {

    private static final String DEFAULT_CONSENT_VERSION = "1.0";

    private final UserConsentRecordRepository consentRecordRepository;

    /**
     * 查询当前用户的 LLM 服务与隐私政策同意状态。
     */
    @Transactional(readOnly = true)
    public ConsentStatusResponse getConsentStatus(Long userId) {
        boolean llmService = consentRecordRepository.hasConsented(userId, ConsentType.LLM_SERVICE);
        boolean privacyPolicy = consentRecordRepository.hasConsented(userId, ConsentType.PRIVACY_POLICY);
        return new ConsentStatusResponse(llmService, privacyPolicy);
    }

    /**
     * 提交用户同意记录。
     */
    @Transactional
    public void submitConsents(Long userId, ConsentSubmitRequest request, HttpServletRequest httpRequest) {
        String version = StringUtils.hasText(request.getVersion()) ? request.getVersion() : DEFAULT_CONSENT_VERSION;
        String ipAddress = extractClientIp(httpRequest);
        String userAgent = httpRequest != null ? httpRequest.getHeader("User-Agent") : null;

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
            consentRecordRepository.save(record);
            log.info("[ConsentService] 用户同意记录已保存: userId={}, type={}, version={}", userId, type, version);
        }
    }

    /**
     * 检查用户是否已同意 LLM 服务条款。
     */
    @Transactional(readOnly = true)
    public boolean hasConsentedLlmService(Long userId) {
        return consentRecordRepository.hasConsented(userId, ConsentType.LLM_SERVICE);
    }

    /**
     * 在调用外部模型前同时校验 LLM 服务和隐私政策同意，避免只依赖前端弹窗。
     */
    @Transactional(readOnly = true)
    public void requireAiProcessingConsent(Long userId) {
        boolean llmService = consentRecordRepository.hasConsented(userId, ConsentType.LLM_SERVICE);
        boolean privacyPolicy = consentRecordRepository.hasConsented(userId, ConsentType.PRIVACY_POLICY);
        if (!llmService || !privacyPolicy) {
            throw new BusinessException(UserErrorCode.CONSENT_REQUIRED, "请先同意 LLM 服务条款和隐私政策");
        }
    }

    private ConsentType parseConsentType(String value) {
        try {
            return ConsentType.valueOf(value.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(UserErrorCode.CONSENT_TYPE_INVALID, "同意类型无效: " + value);
        }
    }

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
