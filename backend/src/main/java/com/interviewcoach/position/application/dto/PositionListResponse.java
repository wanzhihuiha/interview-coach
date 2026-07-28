package com.interviewcoach.position.application.dto;

import java.util.List;
import lombok.Data;

/**
 * 岗位列表响应。
 */
@Data
public class PositionListResponse {

    private List<PositionListItemResponse> content;
    private Long totalElements;
    private Integer totalPages;
    private Integer currentPage;
}
