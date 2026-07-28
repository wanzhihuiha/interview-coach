package com.interviewcoach.user.application.service;

import static com.interviewcoach.user.application.service.UserErrorCode.*;

import com.interviewcoach.common.exception.BusinessException;
import com.interviewcoach.user.application.dto.UserProfileResponse;
import com.interviewcoach.user.application.dto.UserProfileUpdateRequest;
import com.interviewcoach.user.domain.entity.Gender;
import com.interviewcoach.user.domain.entity.User;
import com.interviewcoach.user.domain.entity.UserProfile;
import com.interviewcoach.user.domain.repository.UserProfileRepository;
import com.interviewcoach.user.domain.repository.UserRepository;
import java.util.Optional;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 用户资料与账号管理服务。
 */
@Service
@RequiredArgsConstructor
public class UserProfileService {

    private static final Pattern PHONE_PATTERN = Pattern.compile("^1[3-9]\\d{9}$");

    private final UserRepository userRepository;
    private final UserProfileRepository userProfileRepository;
    private final PasswordEncoder passwordEncoder;
    private final SmsCodeService smsCodeService;

    /**
     * 获取当前用户资料。
     */
    @Transactional(readOnly = true)
    public UserProfileResponse getProfile(Long userId) {
        User user = findUserById(userId);
        UserProfile profile = userProfileRepository.findByUserId(userId).orElseGet(UserProfile::new);
        return toResponse(user, profile);
    }

    /**
     * 更新当前用户资料。
     */
    @Transactional
    public void updateProfile(Long userId, UserProfileUpdateRequest request) {
        UserProfile profile = userProfileRepository.findByUserId(userId)
                .orElseGet(() -> {
                    UserProfile newProfile = new UserProfile();
                    newProfile.setUserId(userId);
                    return newProfile;
                });

        Optional.ofNullable(request.getNickname()).ifPresent(profile::setNickname);
        Optional.ofNullable(request.getAvatar()).ifPresent(profile::setAvatar);
        Optional.ofNullable(request.getGender()).ifPresent(g -> profile.setGender(Gender.valueOf(g)));
        Optional.ofNullable(request.getBirthday()).ifPresent(profile::setBirthday);
        Optional.ofNullable(request.getBio()).ifPresent(profile::setBio);

        userProfileRepository.save(profile);
    }

    /**
     * 修改密码。
     */
    @Transactional
    public void changePassword(Long userId, String oldPassword, String newPassword, String confirmPassword) {
        if (!newPassword.equals(confirmPassword)) {
            throw new BusinessException(PASSWORD_NOT_MATCH, "两次密码输入不一致");
        }
        if (!AuthService.PASSWORD_PATTERN.matcher(newPassword).matches()) {
            throw new BusinessException(PASSWORD_FORMAT_INVALID, "密码格式错误，应为8-20位且包含字母和数字");
        }

        User user = findUserById(userId);
        if (!passwordEncoder.matches(oldPassword, user.getPassword())) {
            throw new BusinessException(OLD_PASSWORD_ERROR, "原密码错误");
        }

        user.setPassword(passwordEncoder.encode(newPassword));
        userRepository.save(user);
    }

    /**
     * 绑定手机号。
     */
    @Transactional
    public void bindPhone(Long userId, String phone, String smsCode) {
        if (phone == null || !PHONE_PATTERN.matcher(phone).matches()) {
            throw new BusinessException(PHONE_FORMAT_INVALID, "手机号格式错误");
        }
        if (userRepository.existsByPhone(phone)) {
            throw new BusinessException(PHONE_ALREADY_EXISTS, "手机号已被绑定");
        }
        if (!smsCodeService.verifyCode(phone, smsCode)) {
            throw new BusinessException(SMS_CODE_INVALID, "验证码错误或已过期");
        }

        User user = findUserById(userId);
        user.setPhone(phone);
        userRepository.save(user);
    }

    /**
     * 绑定微信。
     */
    @Transactional
    public void bindWechat(Long userId, String code) {
        // TODO: 生产环境调用微信服务换取 openid
        throw new UnsupportedOperationException("微信绑定需接入真实微信服务");
    }

    private User findUserById(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(USER_NOT_FOUND, "用户不存在"));
    }

    private UserProfileResponse toResponse(User user, UserProfile profile) {
        UserProfileResponse.ProfileInfo profileInfo = new UserProfileResponse.ProfileInfo();
        profileInfo.setNickname(profile.getNickname());
        profileInfo.setAvatar(profile.getAvatar());
        profileInfo.setGender(profile.getGender() != null ? profile.getGender().name() : null);
        profileInfo.setBirthday(profile.getBirthday());
        profileInfo.setBio(profile.getBio());

        UserProfileResponse response = new UserProfileResponse();
        response.setUserId(user.getId());
        response.setUsername(user.getUsername());
        response.setPhone(maskPhone(user.getPhone()));
        response.setEmail(maskEmail(user.getEmail()));
        response.setRoles(user.getRoleList());
        response.setProfile(profileInfo);
        return response;
    }

    private String maskPhone(String phone) {
        if (phone == null || phone.length() != 11) {
            return phone;
        }
        return phone.substring(0, 3) + "****" + phone.substring(7);
    }

    private String maskEmail(String email) {
        if (email == null || !email.contains("@")) {
            return email;
        }
        int atIndex = email.indexOf('@');
        if (atIndex <= 1) {
            return email;
        }
        return email.charAt(0) + "***" + email.substring(atIndex);
    }
}
