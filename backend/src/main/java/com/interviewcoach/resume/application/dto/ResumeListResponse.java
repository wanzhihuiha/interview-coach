package com.interviewcoach.resume.application.dto;

import java.util.List;
import lombok.Data;

/**
 * 简历列表的零基页码分页响应。
 */
@Data
public class ResumeListResponse {

    /** 当前页的简历摘要，页内没有记录时为空列表。 */
    private List<ResumeListItemResponse> content;
    /** 当前用户全部匹配简历的总条数。 */
    private long totalElements;
    /** 按请求页大小计算的总页数。 */
    private int totalPages;
    /** 当前返回页的零基页码。 */
    private int currentPage;
}
