package com.interviewcoach.user.application.dto;

import java.time.LocalDate;
import lombok.Data;

/**
 * 用户资料更新请求。
 */
@Data
public class UserProfileUpdateRequest {

    private String nickname;
    private String avatar;
    private String gender;
    private LocalDate birthday;
    private String bio;
}
