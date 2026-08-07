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

    private static final Pattern PASSWORD_PATTERN =
            Pattern.compile("^(?=.*[A-Za-z])(?=.*\\d)[A-Za-z\\d@$!%*?&]{8,20}$");

    private UserPasswordPolicy() {
    }

    /**
     * 判断密码是否符合所有格式要求。
     */
    public static boolean isValid(String password) {
        return password != null && PASSWORD_PATTERN.matcher(password).matches();
    }
}
