package com.interviewcoach.resume.interfaces.rest;

import com.interviewcoach.common.response.ApiResponse;
import com.interviewcoach.resume.application.dto.ConfirmResumeRequest;
import com.interviewcoach.resume.application.dto.ResumeDetailResponse;
import com.interviewcoach.resume.application.dto.ResumeListResponse;
import com.interviewcoach.resume.application.dto.ResumeParseStatusResponse;
import com.interviewcoach.resume.application.dto.ResumeProfileAnalysisRetryRequest;
import com.interviewcoach.resume.application.dto.ResumeProfileResponse;
import com.interviewcoach.resume.application.dto.ResumeUploadResponse;
import com.interviewcoach.resume.application.dto.UpdateResumeProfileDraftRequest;
import com.interviewcoach.resume.application.service.ResumeService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 简历模块 REST 接口。
 * 认证用户 ID 来自 Spring Security 上下文，具体简历的资源归属由应用服务联合校验。
 */
@RestController
@RequestMapping("/api/v1/resumes")
@RequiredArgsConstructor
public class ResumeController {

    /** 编排资源归属、状态、持久化、文件和异步任务的简历应用服务。 */
    private final ResumeService resumeService;

    /** 上传文件并登记异步事实解析；成功响应供前端随后轮询任务状态。 */
    @PostMapping("/upload")
    public ApiResponse<ResumeUploadResponse> uploadResume(
            @AuthenticationPrincipal Long userId,
            @RequestPart("file") MultipartFile file,
            @RequestParam("fileType") String fileType) {
        // 将安全上下文用户和上传流交给应用服务，额度、落盘、建档及交接失败均由服务统一处理。
        return ApiResponse.success(resumeService.uploadResume(userId, file, fileType));
    }

    /**
     * 分页返回当前认证用户拥有的简历摘要；页码默认 0，页大小默认 10，默认大小的精确产品依据缺失。
     */
    @GetMapping
    public ApiResponse<ResumeListResponse> listResumes(
            @AuthenticationPrincipal Long userId,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "10") int size) {
        // 应用服务按可信用户 ID 过滤数据并组合正式画像存在性，业务异常交给统一异常处理器。
        return ApiResponse.success(resumeService.listResumes(userId, page, size));
    }

    /** 返回当前认证用户指定简历的文件元数据、解析状态和草稿优先事实内容。 */
    @GetMapping("/{id}")
    public ApiResponse<ResumeDetailResponse> getResumeDetail(
            @AuthenticationPrincipal Long userId,
            @PathVariable("id") Long resumeId) {
        // 应用服务同时查询简历、草稿和正式画像，并在资源不归属时拒绝访问。
        return ApiResponse.success(resumeService.getResumeDetail(userId, resumeId));
    }

    /** 返回正式事实、当前草稿、保留辅助结果和最新分析任务组成的画像聚合响应。 */
    @GetMapping("/{id}/profile")
    public ApiResponse<ResumeProfileResponse> getResumeProfile(
            @AuthenticationPrincipal Long userId,
            @PathVariable("id") Long resumeId) {
        // 聚合结果区分展示旧结果与面试可用性；状态和模式的额外 API 中文 Label 仍属范围外。
        return ApiResponse.success(resumeService.getResumeProfile(userId, resumeId));
    }

    /**
     * 供前端轮询后台解析状态；该只读接口不会触发或重试解析任务。
     */
    @GetMapping("/{id}/parse-status")
    public ApiResponse<ResumeParseStatusResponse> getParseStatus(
            @AuthenticationPrincipal Long userId,
            @PathVariable("id") Long resumeId) {
        // 只读取当前用户的数据库状态和画像存在性，不发布事件或调用模型。
        return ApiResponse.success(resumeService.getParseStatus(userId, resumeId));
    }

    /** 确认当前代次事实草稿为正式画像；成功后可选免费分析失败不会反向撤销确认。 */
    @PutMapping("/{id}/confirm")
    public ApiResponse<Void> confirmResume(
            @AuthenticationPrincipal Long userId,
            @PathVariable("id") Long resumeId,
            @Valid @RequestBody ConfirmResumeRequest request) {
        // 应用服务校验归属、锁和代次，规范化后保存正式事实并删除草稿。
        resumeService.confirmResume(userId, resumeId, request.getParseGeneration(), request.getProfile());
        return ApiResponse.success();
    }

    /** 保存当前解析代次下用户编辑的事实草稿，代次过期时拒绝覆盖。 */
    @PutMapping("/{id}/profile/draft")
    public ApiResponse<Void> updateProfileDraft(
            @AuthenticationPrincipal Long userId,
            @PathVariable("id") Long resumeId,
            @Valid @RequestBody UpdateResumeProfileDraftRequest request) {
        // 应用服务在数据库行锁内复核归属、状态及解析代次后写入草稿。
        resumeService.updateProfileDraft(
                userId, resumeId, request.getParseGeneration(), request.getProfile());
        return ApiResponse.success();
    }

    /** 为当前简历创建新的事实解析代次并异步交接，保留已有正式画像。 */
    @PutMapping("/{id}/reparse")
    public ApiResponse<ResumeUploadResponse> reparseResume(
            @AuthenticationPrincipal Long userId,
            @PathVariable("id") Long resumeId) {
        // 应用服务完成锁、额度、许可、状态登记和事件交接，失败时按数据库终态结算补偿。
        return ApiResponse.success(resumeService.reparseResume(userId, resumeId));
    }

    /** 提交公开的 REGENERATE 或 REFINE 辅助分析任务；敏感反馈只在当前调用链和事件内存中传递。 */
    @PostMapping("/{id}/analysis/retry")
    public ApiResponse<Void> retryProfileAnalysis(
            @AuthenticationPrincipal Long userId,
            @PathVariable("id") Long resumeId,
            @RequestBody(required = false) ResumeProfileAnalysisRetryRequest request) {
        // 应用服务校验公开模式、旧结果和反馈，再依次完成许可、额度、数据库登记与异步交接。
        resumeService.retryProfileAnalysis(userId, resumeId, request);
        return ApiResponse.success();
    }

    /** 删除当前用户未被面试锁定的简历及关联数据库记录，并在提交后尽力删除本地文件。 */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> deleteResume(
            @AuthenticationPrincipal Long userId,
            @PathVariable("id") Long resumeId) {
        // 应用服务在用户变更锁内校验归属和面试锁，数据库删除成功后执行文件副作用。
        resumeService.deleteResume(userId, resumeId);
        return ApiResponse.success();
    }
}
