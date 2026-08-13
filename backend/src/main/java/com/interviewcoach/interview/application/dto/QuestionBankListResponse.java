package com.interviewcoach.interview.application.dto;

import java.util.List;
import lombok.Data;

/**
 * 管理端永久题库的分页响应；分页元数据直接来自 Spring Data 查询结果。
 */
@Data
public class QuestionBankListResponse {

    /** 当前页映射后的永久题目列表。 */
    private List<QuestionBankItem> content;

    /** 满足当前筛选条件的永久题目总数。 */
    private Long totalElements;

    /** 按请求页大小计算出的总页数。 */
    private Integer totalPages;

    /** 当前页的零基页码。 */
    private Integer currentPage;
}
