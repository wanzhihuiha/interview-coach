package com.interviewcoach.user.application.service;

import static com.interviewcoach.user.application.service.UserErrorCode.*;

import com.interviewcoach.common.exception.BusinessException;
import com.interviewcoach.common.exception.TokenRefreshException;
import com.interviewcoach.common.security.JwtUtil;
import com.interviewcoach.user.application.dto.LoginResponse;
import io.jsonwebtoken.Claims;
import com.interviewcoach.user.domain.entity.User;
import com.interviewcoach.user.domain.entity.UserProfile;
import com.interviewcoach.user.domain.repository.UserProfileRepository;
import com.interviewcoach.user.domain.repository.UserRepository;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 认证服务，处理注册、登录、Token 生成。
 */
@Service
@RequiredArgsConstructor
public class AuthService {

    private static final Pattern USERNAME_PATTERN = Pattern.compile("^[a-zA-Z0-9_]{4,20}$");
    static final Pattern PASSWORD_PATTERN = Pattern.compile("^(?=.*[A-Za-z])(?=.*\\d)[A-Za-z\\d@$!%*?&]{8,20}$");
    private static final Pattern PHONE_PATTERN = Pattern.compile("^1[3-9]\\d{9}$");

    private final UserRepository userRepository;
    private final UserProfileRepository userProfileRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final SmsCodeService smsCodeService;
    private final WechatService wechatService;

    /**
     * 用户名密码注册，可选绑定手机号。
     */
    @Transactional
    public LoginResponse registerByUsername(String username, String password, String confirmPassword,
                                            String phone, String smsCode) {
        validateUsername(username);
        validatePassword(password);
        if (confirmPassword != null && !confirmPassword.isBlank() && !password.equals(confirmPassword)) {
            throw new BusinessException(PASSWORD_NOT_MATCH, "两次密码输入不一致");
        }
        if (userRepository.existsByUsername(username)) {
            throw new BusinessException(USERNAME_ALREADY_EXISTS, "用户名已存在");
        }

        User user = new User();
        user.setUsername(username);
        user.setPassword(passwordEncoder.encode(password));

        if (phone != null && !phone.isBlank()) {
            validatePhone(phone);
            if (!smsCodeService.verifyCode(phone, smsCode)) {
                throw new BusinessException(SMS_CODE_INVALID, "验证码错误或已过期");
            }
            if (userRepository.existsByPhone(phone)) {
                throw new BusinessException(PHONE_ALREADY_EXISTS, "手机号已存在");
            }
            user.setPhone(phone);
        }

        userRepository.save(user);
        createEmptyProfile(user.getId());
        return buildLoginResponse(user);
    }

    /**
     * 用户名密码登录。
     */
    @Transactional(readOnly = true)
    public LoginResponse loginByUsername(String username, String password) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new BusinessException(USER_NOT_FOUND, "用户不存在"));
        if (!passwordEncoder.matches(password, user.getPassword())) {
            throw new BusinessException(PASSWORD_ERROR, "密码错误");
        }
        return buildLoginResponse(user);
    }

    /**
     * 手机号验证码登录（手机号不存在则自动注册）。
     */
    @Transactional
    public LoginResponse loginByPhone(String phone, String smsCode) {
        validatePhone(phone);
        if (!smsCodeService.verifyCode(phone, smsCode)) {
            throw new BusinessException(SMS_CODE_INVALID, "验证码错误或已过期");
        }

        Optional<User> optionalUser = userRepository.findByPhone(phone);
        User user = optionalUser.orElseGet(() -> createUserByPhone(phone));
        return buildLoginResponse(user);
    }

    /**
     * 微信登录（OpenID 不存在则自动注册）。
     */
    @Transactional
    public LoginResponse loginByWechat(String code) {
        String openid = wechatService.codeToOpenid(code);
        User user = userRepository.findByOpenid(openid)
                .orElseGet(() -> createUserByOpenid(openid));
        return buildLoginResponse(user);
    }

    /**
     * 生成登录响应。
     */
    private LoginResponse buildLoginResponse(User user) {
        if (!user.isActive()) {
            throw new BusinessException(ACCOUNT_DISABLED, "账号已被禁用");
        }
        String token = jwtUtil.generateToken(user.getId(), user.getUsername(), user.getRoleList());
        LoginResponse.UserInfo userInfo = new LoginResponse.UserInfo(
                user.getId(),
                user.getUsername(),
                maskPhone(user.getPhone()),
                user.getRoleList()
        );
        return new LoginResponse(token, userInfo);
    }

    /**
     * 刷新 token。
     *
     * <p>只要原 token 签名有效且未超出续期宽限期，就颁发新 token，
     * 保证长会话（如面试）不会因 token 过期而中断。</p>
     */
    @Transactional(readOnly = true)
    public LoginResponse refreshToken(String token) {
        if (!jwtUtil.canRefresh(token)) {
            throw new TokenRefreshException("Token 已失效或超出续期宽限期，请重新登录");
        }
        Claims claims = jwtUtil.parseTokenIgnoringExpiration(token);
        Long userId = Long.valueOf(claims.getSubject());
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(USER_NOT_FOUND, "用户不存在"));
        return buildLoginResponse(user);
    }

    /**
     * 创建空白用户资料。
     */
    private void createEmptyProfile(Long userId) {
        UserProfile profile = new UserProfile();
        profile.setUserId(userId);
        userProfileRepository.save(profile);
    }

    private User createUserByPhone(String phone) {
        User user = new User();
        user.setUsername(generateUsername());
        user.setPassword(passwordEncoder.encode(generateRandomPassword()));
        user.setPhone(phone);
        userRepository.save(user);
        createEmptyProfile(user.getId());
        return user;
    }

    private User createUserByOpenid(String openid) {
        User user = new User();
        user.setUsername(generateUsername());
        user.setPassword(passwordEncoder.encode(generateRandomPassword()));
        user.setOpenid(openid);
        userRepository.save(user);
        createEmptyProfile(user.getId());
        return user;
    }

    private void validateUsername(String username) {
        if (username == null || !USERNAME_PATTERN.matcher(username).matches()) {
            throw new BusinessException(USERNAME_FORMAT_INVALID, "用户名格式错误，应为4-20位字母、数字或下划线");
        }
    }

    private void validatePassword(String password) {
        if (password == null || !PASSWORD_PATTERN.matcher(password).matches()) {
            throw new BusinessException(PASSWORD_FORMAT_INVALID, "密码格式错误，应为8-20位且包含字母和数字");
        }
    }

    private void validatePhone(String phone) {
        if (phone == null || !PHONE_PATTERN.matcher(phone).matches()) {
            throw new BusinessException(PHONE_FORMAT_INVALID, "手机号格式错误");
        }
    }

    private String generateUsername() {
        return "user_" + System.currentTimeMillis();
    }

    private String generateRandomPassword() {
        return java.util.UUID.randomUUID().toString().replace("-", "");
    }

    private String maskPhone(String phone) {
        if (phone == null || phone.length() != 11) {
            return phone;
        }
        return phone.substring(0, 3) + "****" + phone.substring(7);
    }
}
