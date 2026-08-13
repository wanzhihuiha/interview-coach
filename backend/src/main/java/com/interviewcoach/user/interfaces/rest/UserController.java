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
 * 用户注册、登录、账号资料与认证凭据相关的 HTTP 适配器。
 *
 * <p>公开端点接收注册、登录、续期和当前 Mock 验证码请求；受保护端点使用 Spring Security 提供的
 * 用户主键查询或修改当前账号。Controller 只负责传输适配和响应包装，业务校验与持久化交给应用服务。</p>
 */
@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class UserController {

    /**
     * 负责注册、三种登录方式和 JWT 续期的认证应用服务。
     */
    private final AuthService authService;

    /**
     * 负责当前用户资料查询、部分更新、改密和手机号绑定的应用服务。
     */
    private final UserProfileService userProfileService;

    /**
     * 负责为公开 Mock 短信端点生成 Redis 验证码并返回其明文的服务。
     */
    private final SmsCodeService smsCodeService;

    /**
     * 使用用户名和密码注册账号，可按请求中的可选手机号及验证码同时绑定手机号。
     *
     * @param request 已完成 Bean Validation 的注册请求，仍由服务执行业务格式和唯一性校验
     * @return 新账号 JWT 及账号摘要
     */
    @PostMapping("/register")
    public ApiResponse<LoginResponse> register(@Valid @RequestBody RegisterRequest request) {
        // 将请求字段交给认证服务；服务在数据库事务内创建账号和空白资料并签发 JWT。
        return ApiResponse.success(authService.registerByUsername(
                request.getUsername(), request.getPassword(), request.getConfirmPassword(),
                request.getPhone(), request.getSmsCode()));
    }

    /**
     * 使用用户名和明文密码登录已有账号。
     *
     * @param request 已校验非空的用户名密码请求；密码属于敏感认证数据
     * @return 新 JWT 及脱敏账号摘要
     */
    @PostMapping("/login")
    public ApiResponse<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        // 认证服务查询账号、校验 BCrypt 密码和账号状态后签发登录响应。
        return ApiResponse.success(authService.loginByUsername(request.getUsername(), request.getPassword()));
    }

    /**
     * 使用 Authorization 请求头中的原 JWT 申请续期。
     *
     * <p>请求头必须以 {@code Bearer } 开头；服务在允许的续期窗口内重新查询用户状态和角色后签发新 JWT。</p>
     *
     * @param authHeader 可选 Authorization 请求头
     * @return 续期后的新 JWT 及当前账号摘要
     */
    @PostMapping("/refresh-token")
    public ApiResponse<LoginResponse> refreshToken(
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        if (!StringUtils.hasText(authHeader) || !authHeader.startsWith("Bearer ")) {
            throw new TokenRefreshException("缺少或格式错误的认证信息");
        }
        String token = authHeader.substring(7);
        // 去除 Bearer 前缀后交给认证服务判断续期窗口、解析主体并重新加载账号。
        return ApiResponse.success(authService.refreshToken(token));
    }

    /**
     * 使用手机号和一次性验证码登录，手机号未注册时自动创建账号与空白资料。
     *
     * @param request 已校验非空的手机号验证码请求
     * @return 已有或新建账号的新 JWT 及账号摘要
     */
    @PostMapping("/login/phone")
    public ApiResponse<LoginResponse> loginByPhone(@Valid @RequestBody PhoneLoginRequest request) {
        // 认证服务消费 Redis 验证码，并按手机号查询或创建账号后签发 JWT。
        return ApiResponse.success(authService.loginByPhone(request.getPhone(), request.getSmsCode()));
    }

    /**
     * 使用 code 进入当前模拟微信登录流程。
     *
     * <p>当前服务只在本地从 code 派生模拟 OpenID，不访问微信 API，也不验证真实微信身份。</p>
     *
     * @param request 已校验 code 非空的模拟微信登录请求
     * @return 已有或新建模拟 OpenID 账号的新 JWT 及账号摘要
     */
    @PostMapping("/login/wechat")
    public ApiResponse<LoginResponse> loginByWechat(@Valid @RequestBody WechatLoginRequest request) {
        // 将 code 交给认证服务完成本地模拟标识派生、账号查询或创建以及 JWT 签发。
        return ApiResponse.success(authService.loginByWechat(request.getCode()));
    }

    /**
     * 为手机号生成当前 Mock 短信验证码。
     *
     * <p>本端点不会发送真实短信；它会把手机号和 6 位明文验证码都放入响应，验证码也会写入标准输出。
     * 明文返回属于当前开发行为，调用方必须按敏感认证数据处理。</p>
     *
     * @param request 已校验手机号非空的 Mock 验证码请求
     * @return 包含 {@code phone} 和明文 {@code code} 的 Map 响应
     */
    @PostMapping("/sms/send")
    public ApiResponse<Map<String, String>> sendSmsCode(@Valid @RequestBody SmsCodeSendRequest request) {
        // 在 Redis 建立验证码和频控 Key，并取得当前 Mock 服务直接返回的明文验证码。
        String code = smsCodeService.sendCode(request.getPhone());
        // 保持现有公共契约，把手机号和敏感验证码明文一起返回给调用方。
        return ApiResponse.success(Map.of("phone", request.getPhone(), "code", code));
    }

    /**
     * 查询当前认证账号及其扩展资料。
     *
     * @param userId Spring Security 从已认证 JWT 主体提供的用户主键
     * @return 脱敏联系方式、英文角色码及资料详情
     */
    @GetMapping("/me")
    public ApiResponse<UserProfileResponse> getProfile(@AuthenticationPrincipal Long userId) {
        // 使用安全上下文中的用户主键聚合账号和资料，客户端不能指定其他用户。
        return ApiResponse.success(userProfileService.getProfile(userId));
    }

    /**
     * 按请求中的非 {@code null} 字段部分更新当前认证用户资料。
     *
     * @param userId Spring Security 从已认证 JWT 主体提供的用户主键
     * @param request 待覆盖的非空资料字段
     * @return 不携带业务数据的成功响应
     */
    @PutMapping("/me")
    public ApiResponse<Void> updateProfile(
            @AuthenticationPrincipal Long userId,
            @Valid @RequestBody UserProfileUpdateRequest request) {
        // 将可信用户主键和部分更新请求交给资料服务查询、创建或保存一对一资料。
        userProfileService.updateProfile(userId, request);
        return ApiResponse.success();
    }

    /**
     * 校验当前密码和新密码规则后，修改当前认证账号的密码密文。
     *
     * @param userId Spring Security 从已认证 JWT 主体提供的用户主键
     * @param request 三个敏感明文密码字段，不应记录或回显
     * @return 不携带业务数据的成功响应
     */
    @PutMapping("/password")
    public ApiResponse<Void> changePassword(
            @AuthenticationPrincipal Long userId,
            @Valid @RequestBody ChangePasswordRequest request) {
        // 资料服务验证确认值、统一密码策略和原密码后，只保存 BCrypt 新密文。
        userProfileService.changePassword(
                userId, request.getOldPassword(), request.getNewPassword(), request.getConfirmPassword());
        return ApiResponse.success();
    }

    /**
     * 为当前认证账号绑定已通过短信验证码校验的手机号。
     *
     * @param userId Spring Security 从已认证 JWT 主体提供的用户主键
     * @param request 目标手机号及敏感的一次性明文验证码
     * @return 不携带业务数据的成功响应
     */
    @PostMapping("/phone/bind")
    public ApiResponse<Void> bindPhone(
            @AuthenticationPrincipal Long userId,
            @Valid @RequestBody BindPhoneRequest request) {
        // 资料服务检查手机号格式和占用、消费 Redis 验证码，再把手机号写入当前账号。
        userProfileService.bindPhone(userId, request.getPhone(), request.getSmsCode());
        return ApiResponse.success();
    }
}
