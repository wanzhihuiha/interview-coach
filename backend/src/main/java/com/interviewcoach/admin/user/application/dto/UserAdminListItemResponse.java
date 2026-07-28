package com.interviewcoach.admin.user.application.dto;

import java.time.LocalDateTime;
import java.util.List;
import lombok.Data;

/**
 * 管理员视角用户列表项响应。
 */
@Data
public class UserAdminListItemResponse {

    private Long userId;
    private String username;
    private String phone;
    private String email;
    private String status;
    private List<String> roles;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
