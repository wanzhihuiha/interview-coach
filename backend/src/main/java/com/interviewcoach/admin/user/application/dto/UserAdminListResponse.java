package com.interviewcoach.admin.user.application.dto;

import java.util.List;
import lombok.Data;

/**
 * 管理员视角用户列表响应。
 */
@Data
public class UserAdminListResponse {

    private List<UserAdminListItemResponse> content;
    private Long totalElements;
    private Integer totalPages;
    private Integer currentPage;
}
