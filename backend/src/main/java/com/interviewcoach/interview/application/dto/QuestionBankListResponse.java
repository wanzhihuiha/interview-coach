package com.interviewcoach.interview.application.dto;

import java.util.List;
import lombok.Data;

/**
 * 题库列表分页响应。
 */
@Data
public class QuestionBankListResponse {

    private List<QuestionBankItem> content;
    private Long totalElements;
    private Integer totalPages;
    private Integer currentPage;
}
