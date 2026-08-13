package com.interviewcoach.user.application.dto;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 认证服务签发并由登录、注册或令牌续期接口返回的认证响应。
 *
 * <p>响应同时携带后续请求使用的 JWT 和当前账号摘要；调用方必须把令牌按敏感凭据处理。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class LoginResponse {

    /**
     * 后续请求放入 {@code Authorization: Bearer ...} 的 JWT，属于敏感认证凭据，不应记录或公开。
     */
    private String token;

    /**
     * 与本次 JWT 对应的当前登录账号摘要。
     */
    private UserInfo user;

    /**
     * 随认证响应返回给客户端的登录账号摘要。
     *
     * <p>该对象不包含密码；角色目前只返回稳定英文码，尚未在此公共响应中提供 {@code roleLabels} 中文字段。</p>
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UserInfo {

        /**
         * JWT 主体对应的用户主键，供客户端标识当前登录账号。
         */
        private Long userId;

        /**
         * 当前登录账号的用户名。
         */
        private String username;

        /**
         * 账号绑定手机号；标准 11 位值会被掩码，空值或非 11 位历史值按当前实现原样返回。
         */
        private String phone;

        /**
         * 当前账号的稳定英文角色码列表，例如 {@code USER}、{@code ADMIN}。
         */
        private List<String> roles;
    }
}
