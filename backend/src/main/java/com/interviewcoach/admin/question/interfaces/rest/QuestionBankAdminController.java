package com.interviewcoach.admin.question.interfaces.rest;

import com.interviewcoach.interview.application.dto.QuestionBankItem;
import com.interviewcoach.interview.application.dto.QuestionBankListResponse;
import com.interviewcoach.admin.question.application.service.QuestionBankAdminService;
import com.interviewcoach.common.response.ApiResponse;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 题库管理后台接口：管理员审核临时 RAG 题目并维护永久 RAG。
 */
@RestController
@RequestMapping("/api/v1/admin/question-bank")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class QuestionBankAdminController {

    private final QuestionBankAdminService questionBankAdminService;

    /**
     * 查询待审核的临时题目列表。
     */
    @GetMapping("/pending")
    public ApiResponse<List<QuestionBankItem>> listPending(
            @RequestParam(value = "jobCategory", required = false) String jobCategory,
            @RequestParam(value = "phase", required = false) String phase) {
        return ApiResponse.success(questionBankAdminService.listPendingQuestions(jobCategory, phase));
    }

    /**
     * 分页查询永久题库内容。
     */
    @GetMapping("/permanent")
    public ApiResponse<QuestionBankListResponse> listPermanent(
            @RequestParam(value = "jobCategory", required = false) String jobCategory,
            @RequestParam(value = "phase", required = false) String phase,
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "20") int size) {
        return ApiResponse.success(questionBankAdminService.listPermanentQuestions(
                jobCategory, phase, keyword, page, size));
    }

    /**
     * 更新待审核的临时题目内容、参考答案及难度等级。
     */
    @PutMapping("/{id}")
    public ApiResponse<Void> update(@PathVariable("id") Long temporaryQuestionId,
                                    @RequestBody QuestionBankItem item) {
        questionBankAdminService.updateTemporaryQuestion(temporaryQuestionId, item);
        return ApiResponse.success();
    }

    /**
     * 将指定的临时题目审核通过并加入永久 RAG。
     */
    @PostMapping("/{id}/promote")
    public ApiResponse<Long> promote(@PathVariable("id") Long temporaryQuestionId) {
        return ApiResponse.success(questionBankAdminService.promoteToPermanent(temporaryQuestionId));
    }

    /**
     * 拒绝指定的临时题目。
     */
    @PostMapping("/{id}/reject")
    public ApiResponse<Void> reject(@PathVariable("id") Long temporaryQuestionId) {
        questionBankAdminService.rejectTemporaryQuestion(temporaryQuestionId);
        return ApiResponse.success();
    }
}
