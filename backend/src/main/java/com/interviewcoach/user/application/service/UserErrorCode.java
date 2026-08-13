package com.interviewcoach.user.application.service;

/**
 * 用户域服务抛出 {@code BusinessException} 时使用的稳定业务错误码。
 *
 * <p>这些整数由全局异常处理流程写入 API 响应的业务码字段，不是 HTTP 状态码。</p>
 */
public final class UserErrorCode {

    private UserErrorCode() {
    }

    /**
     * 注册时用户名已经被其他账号占用。
     */
    public static final int USERNAME_ALREADY_EXISTS = 1001;

    /**
     * 注册或修改密码时，新密码与确认值不一致。
     */
    public static final int PASSWORD_NOT_MATCH = 1002;

    /**
     * 注册用户名不符合 4～20 位字母、数字或下划线规则。
     */
    public static final int USERNAME_FORMAT_INVALID = 1003;

    /**
     * 注册或修改密码时，明文不符合统一密码策略。
     */
    public static final int PASSWORD_FORMAT_INVALID = 1004;

    /**
     * 注册、手机号登录或绑定手机号时，手机号不符合当前固定格式规则。
     */
    public static final int PHONE_FORMAT_INVALID = 1005;

    /**
     * 短信验证码不存在、已过期或与用户输入不匹配。
     */
    public static final int SMS_CODE_INVALID = 1006;

    /**
     * 预留的短信发送频率业务码；当前 {@link SmsCodeService} 未使用该常量，频控命中时抛出无业务码异常。
     */
    public static final int SMS_SEND_TOO_FREQUENT = 1007;

    /**
     * 注册或绑定手机号时，手机号已经被任一账号占用；绑定当前账号已有的同一手机号也会命中。
     *
     * <p>源码当前使用 {@code 1008}，其分配依据缺失；旧用户模块设计记录为 {@code 3002}，两者冲突尚未统一。</p>
     */
    public static final int PHONE_ALREADY_EXISTS = 1008;

    /**
     * 登录、续期或资料操作查询不到目标用户。
     */
    public static final int USER_NOT_FOUND = 2001;

    /**
     * 用户名密码登录时，输入明文与已保存 BCrypt 密文不匹配。
     */
    public static final int PASSWORD_ERROR = 2002;

    /**
     * 账号状态为禁用，认证服务拒绝签发新的登录或续期令牌。
     */
    public static final int ACCOUNT_DISABLED = 2003;

    /**
     * 修改密码时，输入的当前密码与已保存密文不匹配。
     */
    public static final int OLD_PASSWORD_ERROR = 3001;

    /**
     * 提交协议同意时包含无法解析的类型码；当前 {@code 4001} 的分配依据缺失。
     */
    public static final int CONSENT_TYPE_INVALID = 4001;

    /**
     * AI 处理前缺少 LLM 服务条款或隐私政策任一同意记录；当前 {@code 4002} 的分配依据缺失。
     */
    public static final int CONSENT_REQUIRED = 4002;

    /**
     * 管理端提交的目标用户状态无法解析；当前 {@code 6001} 的分配依据缺失。
     */
    public static final int USER_STATUS_INVALID = 6001;
}
