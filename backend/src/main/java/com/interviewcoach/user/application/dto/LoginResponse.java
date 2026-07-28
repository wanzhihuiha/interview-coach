package com.interviewcoach.user.application.dto;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 登录响应。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class LoginResponse {

    private String token;
    private UserInfo user;

    /**
     * 登录用户信息。
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UserInfo {

        private Long userId;
        private String username;
        private String phone;
        private List<String> roles;
    }
}
