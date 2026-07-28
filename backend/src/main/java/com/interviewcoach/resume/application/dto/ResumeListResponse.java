package com.interviewcoach.resume.application.dto;

import java.util.List;
import lombok.Data;

/**
 * 简历列表分页响应。
 */
@Data
public class ResumeListResponse {

    private List<ResumeListItemResponse> content;
    private long totalElements;
    private int totalPages;
    private int currentPage;
}
