package com.interviewcoach.user.interfaces.rest;

import com.interviewcoach.common.exception.TokenRefreshException;
import com.interviewcoach.common.response.ApiResponse;
import com.interviewcoach.user.application.dto.BindPhoneRequest;
import com.interviewcoach.user.application.dto.ChangePasswordRequest;
import com.interviewcoach.user.application.dto.LoginRequest;
import com.interviewcoach.user.application.dto.LoginResponse;
import com.interviewcoach.user.application.dto.PhoneLoginRequest;
import com.interviewcoach.user.application.dto.RegisterRequest;
import com.interviewcoach.user.application.dto.SmsCodeSendRequest;
import com.interviewcoach.user.application.dto.UserProfileResponse;
import com.interviewcoach.user.application.dto.UserProfileUpdateRequest;
import com.interviewcoach.user.application.dto.WechatLoginRequest;
import com.interviewcoach.user.application.service.AuthService;
import com.interviewcoach.user.application.service.SmsCodeService;
import com.interviewcoach.user.application.service.UserProfileService;
import jakarta.validation.Valid;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户模块 REST 接口。
 */
@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class UserController {

    private final AuthService authService;
    private final UserProfileService userProfileService;
    private final SmsCodeService smsCodeService;

    @PostMapping("/register")
    public ApiResponse<LoginResponse> register(@Valid @RequestBody RegisterRequest request) {
        return ApiResponse.success(authService.registerByUsername(
                request.getUsername(), request.getPassword(), request.getConfirmPassword(),
                request.getPhone(), request.getSmsCode()));
    }

    @PostMapping("/login")
    public ApiResponse<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        return ApiResponse.success(authService.loginByUsername(request.getUsername(), request.getPassword()));
    }

    @PostMapping("/refresh-token")
    public ApiResponse<LoginResponse> refreshToken(
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        if (!StringUtils.hasText(authHeader) || !authHeader.startsWith("Bearer ")) {
            throw new TokenRefreshException("缺少或格式错误的认证信息");
        }
        String token = authHeader.substring(7);
        return ApiResponse.success(authService.refreshToken(token));
    }

    @PostMapping("/login/phone")
    public ApiResponse<LoginResponse> loginByPhone(@Valid @RequestBody PhoneLoginRequest request) {
        return ApiResponse.success(authService.loginByPhone(request.getPhone(), request.getSmsCode()));
    }

    @PostMapping("/login/wechat")
    public ApiResponse<LoginResponse> loginByWechat(@Valid @RequestBody WechatLoginRequest request) {
        return ApiResponse.success(authService.loginByWechat(request.getCode()));
    }

    @PostMapping("/sms/send")
    public ApiResponse<Map<String, String>> sendSmsCode(@Valid @RequestBody SmsCodeSendRequest request) {
        String code = smsCodeService.sendCode(request.getPhone());
        return ApiResponse.success(Map.of("phone", request.getPhone(), "code", code));
    }

    @GetMapping("/me")
    public ApiResponse<UserProfileResponse> getProfile(@AuthenticationPrincipal Long userId) {
        return ApiResponse.success(userProfileService.getProfile(userId));
    }

    @PutMapping("/me")
    public ApiResponse<Void> updateProfile(
            @AuthenticationPrincipal Long userId,
            @Valid @RequestBody UserProfileUpdateRequest request) {
        userProfileService.updateProfile(userId, request);
        return ApiResponse.success();
    }

    @PutMapping("/password")
    public ApiResponse<Void> changePassword(
            @AuthenticationPrincipal Long userId,
            @Valid @RequestBody ChangePasswordRequest request) {
        userProfileService.changePassword(
                userId, request.getOldPassword(), request.getNewPassword(), request.getConfirmPassword());
        return ApiResponse.success();
    }

    @PostMapping("/phone/bind")
    public ApiResponse<Void> bindPhone(
            @AuthenticationPrincipal Long userId,
            @Valid @RequestBody BindPhoneRequest request) {
        userProfileService.bindPhone(userId, request.getPhone(), request.getSmsCode());
        return ApiResponse.success();
    }
}
