package com.interviewcoach.position.application.dto;

import java.util.List;
import lombok.Data;

/**
 * 岗位分页查询的 HTTP 响应，供个人、公共、首页组合和管理员公共列表复用。
 */
@Data
public class PositionListResponse {

    /** 当前页按稳定排序返回的岗位列表项；当前页没有记录时为空列表。 */
    private List<PositionListItemResponse> content;
    /** 满足本次资源归属、公共可见性和归档条件的记录总数。 */
    private Long totalElements;
    /** 按本次每页大小计算出的总页数；没有记录时为 0。 */
    private Integer totalPages;
    /** 当前零基页码，第一页为 0。 */
    private Integer currentPage;
}
