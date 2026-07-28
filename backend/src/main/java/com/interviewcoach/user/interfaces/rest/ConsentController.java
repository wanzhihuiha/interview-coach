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
 * 用户同意协议接口，用于前端首次使用时的 LLM 服务条款与隐私政策弹窗。
 */
@RestController
@RequestMapping("/api/v1/users/consents")
@RequiredArgsConstructor
public class ConsentController {

    private final ConsentService consentService;

    /**
     * 查询当前用户的同意状态。
     */
    @GetMapping("/status")
    public ApiResponse<ConsentStatusResponse> getStatus(@AuthenticationPrincipal Long userId) {
        return ApiResponse.success(consentService.getConsentStatus(userId));
    }

    /**
     * 提交用户对 LLM 服务条款与隐私政策的同意。
     */
    @PostMapping
    public ApiResponse<Void> submit(
            @AuthenticationPrincipal Long userId,
            @Valid @RequestBody ConsentSubmitRequest request,
            HttpServletRequest httpRequest) {
        consentService.submitConsents(userId, request, httpRequest);
        return ApiResponse.success();
    }
}
