package com.interviewcoach.admin.audit.application.dto;

import java.util.List;
import lombok.Data;

/**
 * 管理端审计日志的分页响应。
 *
 * <p>由审计日志管理服务根据 Spring Data 分页结果组装，经管理端 HTTP 接口返回。</p>
 */
@Data
public class AuditLogListResponse {

    /**
     * 当前页的审计日志，元素已转换为管理端展示结构。
     */
    private List<AuditLogItemResponse> content;

    /**
     * 当前筛选条件命中的记录总数，不是当前页元素数量。
     */
    private Long totalElements;

    /**
     * 按请求页大小计算出的总页数；没有命中记录时为 {@code 0}。
     */
    private Integer totalPages;

    /**
     * 仓储实际返回的零基页码，管理端展示为一基页码时需要自行转换。
     */
    private Integer currentPage;
}
