package com.interviewcoach.user.domain.entity;

/**
 * 用户账号在数据库中的稳定状态编码与管理端中文展示名。
 *
 * <p>认证服务在签发新的登录或续期 JWT 前检查该状态，管理端同时返回英文编码和中文 Label。
 * 当前 JWT 过滤器不会为每次请求重新查询本状态，因此本枚举只描述现有检查边界，不表示旧令牌即时失效。</p>
 */
public enum UserStatus {
    /**
     * 正常账号，允许认证服务签发新的登录或续期令牌，管理端展示为“正常”。
     */
    ACTIVE("正常"),

    /**
     * 禁用账号，认证服务拒绝签发新的登录或续期令牌，管理端展示为“禁用”。
     */
    DISABLED("禁用");

    /**
     * 管理端状态 Label 使用的中文展示名。
     */
    private final String displayName;

    UserStatus(String displayName) {
        this.displayName = displayName;
    }

    /**
     * 返回当前状态的中文展示名。
     */
    public String getDisplayName() {
        return displayName;
    }
}
