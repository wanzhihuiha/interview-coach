package com.interviewcoach.admin.user.application.dto;

import java.time.LocalDateTime;
import java.util.List;
import lombok.Data;

/**
 * 管理员用户列表中的单条账号记录。
 *
 * <p>由用户实体映射后跨 ADMIN HTTP 边界返回，包含账号联系方式原值、状态和角色展示信息；
 * 手机号和邮箱未在该映射中脱敏，不应复用于普通用户接口。</p>
 */
@Data
public class UserAdminListItemResponse {

    /**
     * 用户表中目标账号的主键。
     */
    private Long userId;

    /**
     * 目标账号的登录用户名。
     */
    private String username;

    /**
     * 目标账号保存的手机号原值，未设置时为 {@code null}，该管理响应不执行脱敏。
     */
    private String phone;

    /**
     * 目标账号保存的邮箱原值，未设置时为 {@code null}，该管理响应不执行脱敏。
     */
    private String email;

    /**
     * 账号状态的稳定英文编码；实体状态为空时为 {@code null}。
     */
    private String status;

    /**
     * 与 {@link #status} 对应的中文名称；实体状态为空时为 {@code null}。
     */
    private String statusLabel;

    /**
     * 从实体逗号分隔字段按原顺序解析出的角色稳定编码列表；空白角色字段按 USER 返回。
     */
    private List<String> roles;

    /**
     * 与 {@link #roles} 按索引一一对应的中文角色名称；未知历史编码保留原值。
     */
    private List<String> roleLabels;

    /**
     * 用户实体首次持久化时写入的账号创建本地日期时间，不携带时区。
     */
    private LocalDateTime createTime;

    /**
     * 用户实体最近一次持久化更新时写入的本地日期时间，不携带时区。
     */
    private LocalDateTime updateTime;
}
