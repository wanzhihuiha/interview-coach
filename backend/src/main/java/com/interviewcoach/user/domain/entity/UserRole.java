package com.interviewcoach.user.domain.entity;

import java.util.Locale;

/**
 * 用户账号角色的稳定英文编码与中文展示名定义。
 *
 * <p>英文枚举名用于账号逗号分隔持久化、JWT 和普通用户 API；管理端借助 {@link #displayNameOf(String)}
 * 同时返回中文 Label。登录和资料响应当前仍只有英文角色码，没有 {@code roleLabels} 字段。</p>
 */
public enum UserRole {

    /**
     * 普通求职用户，英文稳定码为 {@code USER}，中文展示名为“普通用户”。
     */
    USER("普通用户"),

    /**
     * 管理后台用户，英文稳定码为 {@code ADMIN}，可进入受管理员角色保护的管理能力。
     */
    ADMIN("管理员");

    /**
     * 管理端响应使用的中文角色名称。
     */
    private final String displayName;

    UserRole(String displayName) {
        this.displayName = displayName;
    }

    /**
     * 返回当前已知角色的中文展示名。
     */
    public String getDisplayName() {
        return displayName;
    }

    /**
     * 将持久化或 API 中的角色文本转换为中文展示名。
     *
     * <p>已知值会先去除首尾空白并按不区分大小写解析；空值返回 {@code null}，未知历史值原样返回，
     * 以便管理端保留而不是丢弃无法识别的编码。</p>
     *
     * @param value 待转换的角色编码或历史文本
     * @return 已知角色的中文名、原始未知值或 {@code null}
     */
    public static String displayNameOf(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT)).getDisplayName();
        } catch (IllegalArgumentException e) {
            return value;
        }
    }
}
