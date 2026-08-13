package com.interviewcoach.user.domain.service;

import java.util.regex.Pattern;

/**
 * 用户密码格式规则。
 *
 * <p>注册、修改密码和首次管理员初始化共用此规则，避免不同入口接受不同格式的密码。
 * 密码必须为 8～20 位，至少包含一个字母和一个数字，并且只能使用 ASCII 字母、数字及
 * {@code @$!%*?&}。</p>
 */
public final class UserPasswordPolicy {

    /**
     * 密码固定格式：8～20 个字符，至少包含一个 ASCII 字母和一个数字，且字符仅限
     * ASCII 字母、数字及 {@code @$!%*?&}。
     *
     * <p>长度、必含字母和数字及允许字符可由 README 的管理员配置约束核验；
     * 放宽会让各入口接受更弱或更广的密码，收紧会拒绝此前允许的新密码。</p>
     */
    private static final Pattern PASSWORD_PATTERN =
            Pattern.compile("^(?=.*[A-Za-z])(?=.*\\d)[A-Za-z\\d@$!%*?&]{8,20}$");

    private UserPasswordPolicy() {
    }

    /**
     * 判断明文密码是否同时满足长度、字符集合、至少一个字母和至少一个数字的要求。
     *
     * @param password 注册、改密或管理员初始化提供的明文密码；可为 {@code null}
     * @return 全部规则满足时为 {@code true}，空值或任一规则不满足时为 {@code false}
     */
    public static boolean isValid(String password) {
        return password != null && PASSWORD_PATTERN.matcher(password).matches();
    }
}
