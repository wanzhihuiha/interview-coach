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
import com.interviewcoach.user.domain.service.UserPasswordPolicy;
import java.util.Optional;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 当前用户账号与扩展资料的查询和变更应用服务。
 *
 * <p>用户接口使用安全上下文中的用户主键调用本服务；本服务协调账号仓储、资料仓储、密码编码器和
 * Redis 短信验证码服务，负责资料部分更新、改密和手机号绑定，并构造脱敏的资料响应。</p>
 */
@Service
@RequiredArgsConstructor
public class UserProfileService {

    /**
     * 当前手机号固定校验规则：以 1 开头、第二位为 3～9、共 11 位数字。
     * 该精确号段规则的项目依据缺失；调整会改变手机号绑定入口的准入范围。
     */
    private static final Pattern PHONE_PATTERN = Pattern.compile("^1[3-9]\\d{9}$");

    /**
     * 负责读取当前账号、检查手机号占用并保存账号变更的用户仓储。
     */
    private final UserRepository userRepository;

    /**
     * 负责读取、创建和保存一对一用户扩展资料的仓储。
     */
    private final UserProfileRepository userProfileRepository;

    /**
     * 负责校验原密码并将新明文密码编码为 BCrypt 密文的安全组件。
     */
    private final PasswordEncoder passwordEncoder;

    /**
     * 负责从 Redis 校验并消费绑定手机号所需一次性验证码的服务。
     */
    private final SmsCodeService smsCodeService;

    /**
     * 聚合指定账号及其一对一资料，返回经过掩码处理的当前用户响应。
     *
     * <p>资料记录不存在时仅为本次响应构造一个未持久化的默认对象，其中性别为 {@code UNKNOWN}，
     * 不会在查询事务中补写数据库。</p>
     *
     * @param userId 安全上下文提供的当前用户主键
     * @return 账号和扩展资料聚合响应
     */
    @Transactional(readOnly = true)
    public UserProfileResponse getProfile(Long userId) {
        // 先确认当前用户仍存在，空结果映射为用户不存在业务错误。
        User user = findUserById(userId);
        // 查询一对一资料；缺失时只构造未持久化的默认资料供响应映射使用。
        UserProfile profile = userProfileRepository.findByUserId(userId).orElseGet(UserProfile::new);
        // 将账号与资料转换为脱敏 HTTP 响应，角色和性别保持英文码。
        return toResponse(user, profile);
    }

    /**
     * 按请求中的非 {@code null} 字段部分更新当前用户资料。
     *
     * <p>资料不存在时创建并关联当前用户；性别字符串直接调用 {@link Gender#valueOf(String)}，
     * 因此当前解析区分大小写且不去除首尾空白，非法值会使事务失败。</p>
     *
     * @param userId 安全上下文提供的当前用户主键
     * @param request 待覆盖的非空资料字段
     */
    @Transactional
    public void updateProfile(Long userId, UserProfileUpdateRequest request) {
        // 查询当前用户的一对一资料；不存在时创建带所属用户主键的新实体。
        UserProfile profile = userProfileRepository.findByUserId(userId)
                .orElseGet(() -> {
                    UserProfile newProfile = new UserProfile();
                    newProfile.setUserId(userId);
                    return newProfile;
                });

        // 仅应用非 null 字段；字符串空值会被保留并写入，性别按大小写敏感的枚举名解析。
        Optional.ofNullable(request.getNickname()).ifPresent(profile::setNickname);
        Optional.ofNullable(request.getAvatar()).ifPresent(profile::setAvatar);
        Optional.ofNullable(request.getGender()).ifPresent(g -> profile.setGender(Gender.valueOf(g)));
        Optional.ofNullable(request.getBirthday()).ifPresent(profile::setBirthday);
        Optional.ofNullable(request.getBio()).ifPresent(profile::setBio);

        // 保存新建或变更后的资料，非法枚举或数据库错误会回滚本次事务。
        userProfileRepository.save(profile);
    }

    /**
     * 校验确认值、统一密码策略和当前密码后，替换指定账号的密码密文。
     *
     * @param userId 安全上下文提供的当前用户主键
     * @param oldPassword 用于验证账号持有权的当前明文密码
     * @param newPassword 待编码保存的新明文密码
     * @param confirmPassword 用于和新密码逐字比较的确认值
     */
    @Transactional
    public void changePassword(Long userId, String oldPassword, String newPassword, String confirmPassword) {
        // 先比较新密码与确认值，不一致时不查询账号或编码密码。
        if (!newPassword.equals(confirmPassword)) {
            throw new BusinessException(PASSWORD_NOT_MATCH, "两次密码输入不一致");
        }
        // 复用注册和管理员初始化共用的密码策略，避免不同入口接受不同格式。
        if (!UserPasswordPolicy.isValid(newPassword)) {
            throw new BusinessException(PASSWORD_FORMAT_INVALID,
                    "密码格式错误，应为8-20位，至少包含一个字母和一个数字，且只能使用字母、数字及@$!%*?&");
        }

        // 从数据库加载安全上下文所指账号，不存在时停止修改。
        User user = findUserById(userId);
        // 比较当前明文密码与已保存 BCrypt 密文，失败时不生成或保存新密文。
        if (!passwordEncoder.matches(oldPassword, user.getPassword())) {
            throw new BusinessException(OLD_PASSWORD_ERROR, "原密码错误");
        }

        // 只将 BCrypt 编码结果写回账号实体，不持久化请求中的明文密码。
        user.setPassword(passwordEncoder.encode(newPassword));
        // 保存密码变更；数据库失败会回滚本次事务。
        userRepository.save(user);
    }

    /**
     * 在格式、唯一性和短信验证码均通过后，为当前账号绑定手机号。
     *
     * <p>当前顺序先查手机号占用，再校验验证码；验证码匹配成功会删除 Redis 验证码 Key，
     * 后续用户查询或数据库保存失败时不会恢复该验证码。</p>
     *
     * @param userId 安全上下文提供的当前用户主键
     * @param phone 准备绑定的手机号
     * @param smsCode 手机号对应的一次性明文验证码
     */
    @Transactional
    public void bindPhone(Long userId, String phone, String smsCode) {
        // 先按当前固定格式校验目标手机号，非法值在数据库和 Redis 访问前失败。
        if (phone == null || !PHONE_PATTERN.matcher(phone).matches()) {
            throw new BusinessException(PHONE_FORMAT_INVALID, "手机号格式错误");
        }
        // 在消费验证码前查询手机号是否已被任一账号占用。
        if (userRepository.existsByPhone(phone)) {
            throw new BusinessException(PHONE_ALREADY_EXISTS, "手机号已被绑定");
        }
        // 匹配成功会删除 Redis 验证码 Key；失败时不修改账号。
        if (!smsCodeService.verifyCode(phone, smsCode)) {
            throw new BusinessException(SMS_CODE_INVALID, "验证码错误或已过期");
        }

        // 验证码通过后加载当前账号，确保绑定目标来自安全上下文而不是请求体。
        User user = findUserById(userId);
        user.setPhone(phone);
        // 持久化手机号绑定；数据库失败不会恢复已消费的 Redis 验证码。
        userRepository.save(user);
    }

    /**
     * 当前未接入的微信绑定入口。
     *
     * <p>无论用户主键和 code 为何，本方法都会立即抛出 {@link UnsupportedOperationException}，
     * 不调用微信服务、不查询或写入数据库，也不会修改账号 OpenID。</p>
     *
     * @param userId 当前未使用的用户主键
     * @param code 当前未使用的微信 code
     */
    @Transactional
    public void bindWechat(Long userId, String code) {
        // 当前功能固定拒绝，避免把尚未验证的 code 写入账号或误认为已完成真实微信绑定。
        throw new UnsupportedOperationException("微信绑定需接入真实微信服务");
    }

    /**
     * 按用户主键查询账号，并将空结果统一映射为用户不存在业务异常。
     */
    private User findUserById(Long userId) {
        // 查询用户仓储；调用方只有取得实体后才继续资料或账号操作。
        return userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(USER_NOT_FOUND, "用户不存在"));
    }

    /**
     * 将账号和资料实体转换为前端响应，掩盖常规手机号和邮箱并保留英文枚举码。
     */
    private UserProfileResponse toResponse(User user, UserProfile profile) {
        // 资料响应直接携带字段值；性别只返回稳定英文码，不附加中文 Label。
        UserProfileResponse.ProfileInfo profileInfo = new UserProfileResponse.ProfileInfo();
        profileInfo.setNickname(profile.getNickname());
        profileInfo.setAvatar(profile.getAvatar());
        profileInfo.setGender(profile.getGender() != null ? profile.getGender().name() : null);
        profileInfo.setBirthday(profile.getBirthday());
        profileInfo.setBio(profile.getBio());

        // 账号响应对手机号和邮箱执行当前掩码规则，角色只返回英文码列表。
        UserProfileResponse response = new UserProfileResponse();
        response.setUserId(user.getId());
        response.setUsername(user.getUsername());
        response.setPhone(maskPhone(user.getPhone()));
        response.setEmail(maskEmail(user.getEmail()));
        response.setRoles(user.getRoleList());
        response.setProfile(profileInfo);
        return response;
    }

    /**
     * 掩盖标准 11 位手机号的中间四位；空值或非 11 位值按当前兼容行为原样返回。
     */
    private String maskPhone(String phone) {
        if (phone == null || phone.length() != 11) {
            return phone;
        }
        return phone.substring(0, 3) + "****" + phone.substring(7);
    }

    /**
     * 保留常规邮箱首字符和完整域名，并用星号替换其余本地部分。
     * 空值、无 {@code @} 或 {@code @} 前不足两个字符的值按当前兼容行为原样返回。
     */
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
