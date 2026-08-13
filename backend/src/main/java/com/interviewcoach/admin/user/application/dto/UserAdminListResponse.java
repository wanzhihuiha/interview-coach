package com.interviewcoach.admin.user.application.dto;

import java.util.List;
import lombok.Data;

/**
 * 管理员用户列表的分页响应。
 *
 * <p>由用户管理服务根据用户表全量分页结果组装，经 ADMIN HTTP 接口返回。</p>
 */
@Data
public class UserAdminListResponse {

    /**
     * 当前页用户账号，元素包含管理端可见的原始联系方式、状态和角色信息。
     */
    private List<UserAdminListItemResponse> content;

    /**
     * 用户表中的记录总数，不是当前页元素数量。
     */
    private Long totalElements;

    /**
     * 按请求页大小计算出的总页数；用户表为空时为 {@code 0}。
     */
    private Integer totalPages;

    /**
     * 仓储实际返回的零基页码，管理端展示为一基页码时需要自行转换。
     */
    private Integer currentPage;
}
