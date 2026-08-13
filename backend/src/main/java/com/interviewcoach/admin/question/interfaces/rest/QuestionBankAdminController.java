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
 * 管理员审核临时 RAG 题目并维护永久题库的 HTTP 入口。
 *
 * <p>类级权限表达式要求当前 JWT 安全上下文包含 ADMIN 角色；入口负责接收筛选、分页和编辑
 * 请求，待审状态守卫、去重、事务写入及 DTO 映射由题库管理服务完成。</p>
 */
@RestController
@RequestMapping("/api/v1/admin/question-bank")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class QuestionBankAdminController {

    /**
     * 执行临时题目审核和永久题库查询、写入流程。
     */
    private final QuestionBankAdminService questionBankAdminService;

    /**
     * 查询待审核临时题目列表，不执行分页。
     *
     * <p>岗位类别和面试环节只有同时提供且均非空白时才共同参与筛选；只提供其中一个时，
     * 服务会忽略该单项条件并返回全部 PENDING 题目。</p>
     *
     * @param jobCategory 岗位类别稳定编码，需与 {@code phase} 同时提供
     * @param phase       面试环节稳定编码，需与 {@code jobCategory} 同时提供
     * @return 统一响应包装的待审题目列表
     */
    @GetMapping("/pending")
    public ApiResponse<List<QuestionBankItem>> listPending(
            @RequestParam(value = "jobCategory", required = false) String jobCategory,
            @RequestParam(value = "phase", required = false) String phase) {
        // 将两个可选条件交给服务按“同时存在才筛选”的当前规则读取并映射临时题目。
        return ApiResponse.success(questionBankAdminService.listPendingQuestions(jobCategory, phase));
    }

    /**
     * 分页查询永久题库内容。
     *
     * <p>岗位类别和环节执行精确筛选，关键字匹配主题名称或题目正文，结果按创建时间倒序。
     * 页码使用零基约定。当前默认每页 20 条的精确产品依据缺失；调大该值会增加单次数据库
     * 查询、DTO 映射和响应数据量，调小则会增加总页数和翻页请求。</p>
     *
     * @param jobCategory 岗位类别稳定编码，可选
     * @param phase       面试环节稳定编码，可选
     * @param keyword     主题名称或题目正文关键字，可选
     * @param page        零基页码，默认 {@code 0}
     * @param size        每页记录数，当前默认 {@code 20}，精确取值依据缺失
     * @return 统一响应包装的永久题库分页数据
     */
    @GetMapping("/permanent")
    public ApiResponse<QuestionBankListResponse> listPermanent(
            @RequestParam(value = "jobCategory", required = false) String jobCategory,
            @RequestParam(value = "phase", required = false) String phase,
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "20") int size) {
        // 服务规范化关键字并组合仓储筛选；非法分页值由下层分页构造拒绝。
        return ApiResponse.success(questionBankAdminService.listPermanentQuestions(
                jobCategory, phase, keyword, page, size));
    }

    /**
     * 编辑待审核临时题目的主题名称、正文、参考答案及难度。
     *
     * <p>服务只允许编辑 PENDING 题目并要求正文非空；难度非空时按当前固定范围 1～5
     * 截断，精确产品依据缺失。题目不存在、状态不允许或正文为空时返回对应业务错误。</p>
     *
     * @param temporaryQuestionId 待编辑的临时题目 ID
     * @param item                管理端提交的题目字段
     * @return 无业务数据的成功响应
     */
    @PutMapping("/{id}")
    public ApiResponse<Void> update(@PathVariable("id") Long temporaryQuestionId,
                                    @RequestBody QuestionBankItem item) {
        // 由服务在事务内完成状态守卫、字段规范化和保存，失败时不返回成功响应。
        questionBankAdminService.updateTemporaryQuestion(temporaryQuestionId, item);
        return ApiResponse.success();
    }

    /**
     * 将待审核临时题目晋升为永久题目。
     *
     * <p>服务按题目正文与永久库精确去重，并在同一事务内保存永久题目和临时 APPROVED 状态。
     * 题目不存在、已不处于 PENDING 或永久库已有相同正文时返回题库业务错误。</p>
     *
     * @param temporaryQuestionId 待晋升的临时题目 ID
     * @return 新永久题目的数据库 ID
     */
    @PostMapping("/{id}/promote")
    public ApiResponse<Long> promote(@PathVariable("id") Long temporaryQuestionId) {
        // 服务完成待审守卫、永久库去重和两份记录的事务写入，再返回新永久题目 ID。
        return ApiResponse.success(questionBankAdminService.promoteToPermanent(temporaryQuestionId));
    }

    /**
     * 将待审核临时题目标记为已拒绝。
     *
     * @param temporaryQuestionId 待拒绝的临时题目 ID
     * @return 无业务数据的成功响应；不存在或非 PENDING 题目进入业务异常路径
     */
    @PostMapping("/{id}/reject")
    public ApiResponse<Void> reject(@PathVariable("id") Long temporaryQuestionId) {
        // 由服务校验当前状态并在事务内写入 REJECTED，保存失败时不返回成功响应。
        questionBankAdminService.rejectTemporaryQuestion(temporaryQuestionId);
        return ApiResponse.success();
    }
}
