package com.interviewcoach.user.application.dto;

import java.time.LocalDate;
import java.util.List;
import lombok.Data;

/**
 * 用户资料响应。
 */
@Data
public class UserProfileResponse {

    private Long userId;
    private String username;
    private String phone;
    private String email;
    private List<String> roles;
    private ProfileInfo profile;

    /**
     * 用户资料详情。
     */
    @Data
    public static class ProfileInfo {

        private String nickname;
        private String avatar;
        private String gender;
        private LocalDate birthday;
        private String bio;
    }
}
