package com.interviewcoach.user.application.dto;

import java.time.LocalDate;
import java.util.List;
import lombok.Data;

/**
 * 账号信息与扩展资料聚合后的当前用户响应。
 *
 * <p>资料服务从用户账号和一对一资料记录构造本对象，Controller 将其返回给已认证前端；
 * 手机号和邮箱按当前掩码规则处理，角色与性别目前只提供稳定英文码，
 * 没有 {@code roleLabels} 或 {@code genderLabel} 中文字段。</p>
 */
@Data
public class UserProfileResponse {

    /**
     * 当前认证账号的用户主键。
     */
    private Long userId;

    /**
     * 当前认证账号的用户名。
     */
    private String username;

    /**
     * 账号手机号；标准 11 位值会被掩码，空值或非 11 位历史值按当前实现原样返回。
     */
    private String phone;

    /**
     * 账号邮箱；常规地址会保留首字符和域名，空值或不满足当前掩码条件的值原样返回。
     */
    private String email;

    /**
     * 当前账号的稳定英文角色码列表，例如 {@code USER}、{@code ADMIN}。
     */
    private List<String> roles;

    /**
     * 与账号关联的扩展资料；资料记录不存在时由服务构造未持久化的默认详情，其中性别为 {@code UNKNOWN}。
     */
    private ProfileInfo profile;

    /**
     * 返回给前端的用户扩展资料详情。
     *
     * <p>各字段来自一对一资料记录；本对象仅负责传输，不负责持久化。</p>
     */
    @Data
    public static class ProfileInfo {

        /**
         * 用户设置的展示昵称，未设置时为 {@code null}。
         */
        private String nickname;

        /**
         * 用户设置的头像地址，未设置时为 {@code null}。
         */
        private String avatar;

        /**
         * 性别的稳定英文枚举码；资料值为空时返回 {@code null}，当前响应不附带 {@code genderLabel}。
         */
        private String gender;

        /**
         * 用户填写的生日日期，未设置时为 {@code null}。
         */
        private LocalDate birthday;

        /**
         * 用户填写的个人简介，未设置时为 {@code null}。
         */
        private String bio;
    }
}
