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
import com.interviewcoach.user.domain.service.UserPasswordPolicy;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 用户注册、登录和 JWT 续期的应用编排服务。
 *
 * <p>用户接口调用本服务后，本服务协调账号与资料仓储、密码编码器、Redis 短信验证码服务、
 * 模拟微信服务和 JWT 工具，最终返回登录凭据及账号摘要。数据库写入受方法事务管理，
 * 但短信验证码的 Redis 删除不属于数据库事务，后续数据库步骤失败时不会恢复已消费验证码。</p>
 */
@Service
@RequiredArgsConstructor
public class AuthService {

    /**
     * 用户名固定校验规则：4～20 位 ASCII 字母、数字或下划线。
     * 长度来源可由用户模块设计核验；调整字符或长度会改变所有新注册用户名的可接受范围。
     */
    private static final Pattern USERNAME_PATTERN = Pattern.compile("^[a-zA-Z0-9_]{4,20}$");

    /**
     * 当前手机号固定校验规则：以 1 开头、第二位为 3～9、共 11 位数字。
     * 该精确号段规则的项目依据缺失；调整后会同时改变注册和手机号登录的准入范围。
     */
    private static final Pattern PHONE_PATTERN = Pattern.compile("^1[3-9]\\d{9}$");

    /**
     * 负责账号查重、登录查询和账号持久化的用户仓储。
     */
    private final UserRepository userRepository;

    /**
     * 负责为新账号保存一对一空白资料的用户资料仓储。
     */
    private final UserProfileRepository userProfileRepository;

    /**
     * 负责将明文密码编码为 BCrypt 密文并校验登录密码的安全组件。
     */
    private final PasswordEncoder passwordEncoder;

    /**
     * 负责签发 JWT、判断续期窗口并解析续期令牌的令牌工具。
     */
    private final JwtUtil jwtUtil;

    /**
     * 负责从 Redis 校验并消费一次性短信验证码的服务。
     */
    private final SmsCodeService smsCodeService;

    /**
     * 负责把微信 code 确定性转换为模拟 OpenID 的本地服务，当前不访问微信 API。
     */
    private final WechatService wechatService;

    /**
     * 使用用户名和密码注册账号，并可在同一流程中绑定已通过短信校验的手机号。
     *
     * <p>方法先校验输入和唯一性，再保存 BCrypt 密码的账号及空白资料，最后检查账号状态并签发 JWT。
     * 确认密码为空白时不比较；手机号为空白时跳过手机号与验证码校验。账号或资料保存失败会回滚数据库事务。</p>
     *
     * @param username 新账号用户名
     * @param password 新账号明文密码
     * @param confirmPassword 可选的密码确认值
     * @param phone 可选的绑定手机号
     * @param smsCode 可选手机号对应的一次性明文验证码
     * @return 包含 JWT 和新账号摘要的登录响应
     */
    @Transactional
    public LoginResponse registerByUsername(String username, String password, String confirmPassword,
                                            String phone, String smsCode) {
        // 先按注册入口规则校验用户名和密码，非法输入在任何查询或写入前失败。
        validateUsername(username);
        validatePassword(password);
        if (confirmPassword != null && !confirmPassword.isBlank() && !password.equals(confirmPassword)) {
            throw new BusinessException(PASSWORD_NOT_MATCH, "两次密码输入不一致");
        }
        // 查询数据库中的用户名唯一性，已存在时不创建账号。
        if (userRepository.existsByUsername(username)) {
            throw new BusinessException(USERNAME_ALREADY_EXISTS, "用户名已存在");
        }

        User user = new User();
        user.setUsername(username);
        // 只把 BCrypt 编码结果写入实体，注册请求中的明文密码不持久化。
        user.setPassword(passwordEncoder.encode(password));

        if (phone != null && !phone.isBlank()) {
            // 可选手机号存在时先执行固定格式校验，再访问 Redis 或查询手机号占用。
            validatePhone(phone);
            // 校验成功会删除 Redis 验证码 Key；后续查重或数据库失败不会恢复该验证码。
            if (!smsCodeService.verifyCode(phone, smsCode)) {
                throw new BusinessException(SMS_CODE_INVALID, "验证码错误或已过期");
            }
            // 验证码通过后再检查手机号唯一性，已绑定手机号会使本次注册失败。
            if (userRepository.existsByPhone(phone)) {
                throw new BusinessException(PHONE_ALREADY_EXISTS, "手机号已存在");
            }
            user.setPhone(phone);
        }

        // 先持久化账号以取得主键，再用该主键创建一对一空白资料。
        userRepository.save(user);
        createEmptyProfile(user.getId());
        // 统一执行账号状态检查、JWT 签发和脱敏摘要构造后返回给 HTTP 层。
        return buildLoginResponse(user);
    }

    /**
     * 使用用户名和明文密码登录已有账号。
     *
     * <p>账号不存在、密码不匹配或账号被禁用时拒绝登录；成功时不写数据库，直接签发 JWT 并返回账号摘要。</p>
     *
     * @param username 登录用户名
     * @param password 待匹配的明文密码
     * @return 包含新 JWT 和账号摘要的登录响应
     */
    @Transactional(readOnly = true)
    public LoginResponse loginByUsername(String username, String password) {
        // 按用户名查询账号，空结果映射为稳定的用户不存在业务错误。
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new BusinessException(USER_NOT_FOUND, "用户不存在"));
        // 使用密码编码器比较明文与已保存密文，失败时不签发令牌。
        if (!passwordEncoder.matches(password, user.getPassword())) {
            throw new BusinessException(PASSWORD_ERROR, "密码错误");
        }
        // 密码通过后统一检查状态并生成登录响应。
        return buildLoginResponse(user);
    }

    /**
     * 使用手机号和一次性验证码登录，手机号不存在时自动创建账号及空白资料。
     *
     * <p>验证码匹配成功即从 Redis 删除；自动创建或后续数据库步骤失败时不会恢复该验证码。</p>
     *
     * @param phone 登录手机号
     * @param smsCode 一次性明文验证码
     * @return 已有或新建账号对应的登录响应
     */
    @Transactional
    public LoginResponse loginByPhone(String phone, String smsCode) {
        // 先按当前固定格式校验手机号，非法值在读取 Redis 前失败。
        validatePhone(phone);
        // 先消费该手机号的 Redis 验证码，失败时不查询或创建账号。
        if (!smsCodeService.verifyCode(phone, smsCode)) {
            throw new BusinessException(SMS_CODE_INVALID, "验证码错误或已过期");
        }

        // 查询手机号归属；没有关联账号时在当前数据库事务内自动创建账号和资料。
        Optional<User> optionalUser = userRepository.findByPhone(phone);
        User user = optionalUser.orElseGet(() -> createUserByPhone(phone));
        // 对已有或新建账号执行相同状态检查并签发 JWT。
        return buildLoginResponse(user);
    }

    /**
     * 使用本地模拟 OpenID 完成微信登录，OpenID 未关联账号时自动创建账号及空白资料。
     *
     * <p>当前 code 不会发送给微信 API，因此本方法不能证明真实微信身份。</p>
     *
     * @param code 用于本地派生模拟 OpenID 的非空字符串
     * @return 已有或新建账号对应的登录响应
     */
    @Transactional
    public LoginResponse loginByWechat(String code) {
        // 当前仅在本地从 code 派生模拟 OpenID，不执行网络调用或微信真实性校验。
        String openid = wechatService.codeToOpenid(code);
        // 按模拟 OpenID 查询账号；空结果会在当前事务内创建账号和资料。
        User user = userRepository.findByOpenid(openid)
                .orElseGet(() -> createUserByOpenid(openid));
        // 对已有或新建账号执行状态检查并签发 JWT。
        return buildLoginResponse(user);
    }

    /**
     * 检查账号是否允许新登录，并生成 JWT、掩码手机号及英文角色码列表。
     *
     * @param user 已从仓储读取或刚保存的账号
     * @return 可由登录接口返回的认证响应
     */
    private LoginResponse buildLoginResponse(User user) {
        // 禁用账号不能取得新的登录或续期令牌；当前检查发生在响应签发前。
        if (!user.isActive()) {
            throw new BusinessException(ACCOUNT_DISABLED, "账号已被禁用");
        }
        // 将用户主键、用户名和当前英文角色码写入新 JWT，供安全过滤器恢复身份。
        String token = jwtUtil.generateToken(user.getId(), user.getUsername(), user.getRoleList());
        // HTTP 响应仅携带账号摘要，标准 11 位手机号在此进行掩码处理。
        LoginResponse.UserInfo userInfo = new LoginResponse.UserInfo(
                user.getId(),
                user.getUsername(),
                maskPhone(user.getPhone()),
                user.getRoleList()
        );
        return new LoginResponse(token, userInfo);
    }

    /**
     * 在 JWT 工具允许的续期窗口内刷新令牌。
     *
     * <p>原令牌必须可续期；方法从签名有效的令牌中读取用户主键，重新查询账号并检查当前状态后签发新 JWT。
     * 用户不存在或已禁用时不会续期。</p>
     *
     * @param token 不带 Bearer 前缀的原 JWT
     * @return 使用当前账号信息重新签发的登录响应
     */
    @Transactional(readOnly = true)
    public LoginResponse refreshToken(String token) {
        // 先验证签名、令牌形态和续期宽限条件，失败时要求调用方重新登录。
        if (!jwtUtil.canRefresh(token)) {
            throw new TokenRefreshException("Token 已失效或超出续期宽限期，请重新登录");
        }
        // 在已确认可续期后忽略过期时间解析主体，用于重新加载当前账号状态。
        Claims claims = jwtUtil.parseTokenIgnoringExpiration(token);
        Long userId = Long.valueOf(claims.getSubject());
        // 重新查询数据库，避免为已删除账号签发新令牌。
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(USER_NOT_FOUND, "用户不存在"));
        // 使用数据库中的当前角色和状态生成新响应，而不是沿用原令牌声明。
        return buildLoginResponse(user);
    }

    /**
     * 为刚创建的账号持久化一条一对一空白资料记录。
     *
     * @param userId 新账号主键
     */
    private void createEmptyProfile(Long userId) {
        UserProfile profile = new UserProfile();
        profile.setUserId(userId);
        // 与调用方的账号保存处于同一数据库事务，保存失败会使账号创建一并回滚。
        userProfileRepository.save(profile);
    }

    /**
     * 为首次手机号登录创建内部用户名、随机密码密文和绑定手机号，并同步创建空白资料。
     */
    private User createUserByPhone(String phone) {
        User user = new User();
        // 为没有用户名凭据的手机号账号生成当前内部用户名；唯一性最终由数据库约束处理。
        user.setUsername(generateUsername());
        // 自动生成的明文密码只在内存中用于 BCrypt 编码，不返回给客户端。
        user.setPassword(passwordEncoder.encode(generateRandomPassword()));
        user.setPhone(phone);
        // 先保存账号取得主键，再创建一对一资料；两次数据库写入共享外层事务。
        userRepository.save(user);
        createEmptyProfile(user.getId());
        return user;
    }

    /**
     * 为首次模拟微信登录创建内部用户名、随机密码密文和 OpenID，并同步创建空白资料。
     */
    private User createUserByOpenid(String openid) {
        User user = new User();
        // 为没有用户名凭据的模拟微信账号生成当前内部用户名；唯一性最终由数据库约束处理。
        user.setUsername(generateUsername());
        // 自动生成的明文密码只在内存中用于 BCrypt 编码，不返回给客户端。
        user.setPassword(passwordEncoder.encode(generateRandomPassword()));
        user.setOpenid(openid);
        // 先保存账号取得主键，再创建一对一资料；两次数据库写入共享外层事务。
        userRepository.save(user);
        createEmptyProfile(user.getId());
        return user;
    }

    /**
     * 按固定用户名正则校验注册输入，空值或不匹配时抛出用户名格式业务异常。
     */
    private void validateUsername(String username) {
        if (username == null || !USERNAME_PATTERN.matcher(username).matches()) {
            throw new BusinessException(USERNAME_FORMAT_INVALID, "用户名格式错误，应为4-20位字母、数字或下划线");
        }
    }

    /**
     * 使用用户域统一密码策略校验明文，失败时映射为密码格式业务异常。
     */
    private void validatePassword(String password) {
        // 复用注册、改密和管理员初始化共同使用的密码策略，避免入口规则分叉。
        if (!UserPasswordPolicy.isValid(password)) {
            throw new BusinessException(PASSWORD_FORMAT_INVALID,
                    "密码格式错误，应为8-20位，至少包含一个字母和一个数字，且只能使用字母、数字及@$!%*?&");
        }
    }

    /**
     * 按当前固定手机号正则校验输入，空值或不匹配时抛出手机号格式业务异常。
     */
    private void validatePhone(String phone) {
        if (phone == null || !PHONE_PATTERN.matcher(phone).matches()) {
            throw new BusinessException(PHONE_FORMAT_INVALID, "手机号格式错误");
        }
    }

    /**
     * 使用当前毫秒时间戳生成自动注册用户名；本方法自身不查询或保证跨请求唯一性。
     */
    private String generateUsername() {
        return "user_" + System.currentTimeMillis();
    }

    /**
     * 生成去除连字符的 UUID 文本作为自动账号的内部明文密码，随后由调用方立即 BCrypt 编码。
     */
    private String generateRandomPassword() {
        return java.util.UUID.randomUUID().toString().replace("-", "");
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
}
