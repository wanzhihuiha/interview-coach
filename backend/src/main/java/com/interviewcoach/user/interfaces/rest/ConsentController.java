package com.interviewcoach.user.interfaces.rest;

import com.interviewcoach.common.response.ApiResponse;
import com.interviewcoach.user.application.dto.ConsentStatusResponse;
import com.interviewcoach.user.application.dto.ConsentSubmitRequest;
import com.interviewcoach.user.application.service.ConsentService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 当前用户协议同意状态的 HTTP 接口。
 *
 * <p>受认证保护的前端通过本 Controller 查询 LLM 服务条款和隐私政策状态，或提交新的同意事实；
 * 用户主键来自 Spring Security 安全上下文，而不是请求体，具体查询和追加保存交给同意服务。</p>
 */
@RestController
@RequestMapping("/api/v1/users/consents")
@RequiredArgsConstructor
public class ConsentController {

    /**
     * 负责协议状态查询、提交记录追加和服务端同意规则的应用服务。
     */
    private final ConsentService consentService;

    /**
     * 查询当前认证用户的两类协议同意状态并包装为统一 HTTP 响应。
     *
     * @param userId Spring Security 从已认证 JWT 主体提供的用户主键
     * @return 两类协议是否存在历史同意记录的响应
     */
    @GetMapping("/status")
    public ApiResponse<ConsentStatusResponse> getStatus(@AuthenticationPrincipal Long userId) {
        // 使用安全上下文中的用户主键查询数据库状态，避免由客户端指定其他用户。
        return ApiResponse.success(consentService.getConsentStatus(userId));
    }

    /**
     * 为当前认证用户提交的不同协议类型分别追加同意记录。
     *
     * @param userId Spring Security 从已认证 JWT 主体提供的用户主键
     * @param request 前端提交的协议类型码列表和可选版本
     * @param httpRequest 当前请求，用于提取同意记录中的 IP 和 User-Agent 审计文本
     * @return 不携带业务数据的成功响应
     */
    @PostMapping
    public ApiResponse<Void> submit(
            @AuthenticationPrincipal Long userId,
            @Valid @RequestBody ConsentSubmitRequest request,
            HttpServletRequest httpRequest) {
        // 将可信用户身份、已校验请求体和当前请求元数据交给服务，完成去重、解析和追加保存。
        consentService.submitConsents(userId, request, httpRequest);
        return ApiResponse.success();
    }
}
