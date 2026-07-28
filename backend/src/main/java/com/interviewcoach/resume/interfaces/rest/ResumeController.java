package com.interviewcoach.resume.interfaces.rest;

import com.interviewcoach.common.response.ApiResponse;
import com.interviewcoach.resume.application.dto.ConfirmResumeRequest;
import com.interviewcoach.resume.application.dto.ResumeDetailResponse;
import com.interviewcoach.resume.application.dto.ResumeListResponse;
import com.interviewcoach.resume.application.dto.ResumeProfileResponse;
import com.interviewcoach.resume.application.dto.ResumeUploadResponse;
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
 */
@RestController
@RequestMapping("/api/v1/resumes")
@RequiredArgsConstructor
public class ResumeController {

    private final ResumeService resumeService;

    @PostMapping("/upload")
    public ApiResponse<ResumeUploadResponse> uploadResume(
            @AuthenticationPrincipal Long userId,
            @RequestPart("file") MultipartFile file,
            @RequestParam("fileType") String fileType) {
        return ApiResponse.success(resumeService.uploadResume(userId, file, fileType));
    }

    @GetMapping
    public ApiResponse<ResumeListResponse> listResumes(
            @AuthenticationPrincipal Long userId,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "10") int size) {
        return ApiResponse.success(resumeService.listResumes(userId, page, size));
    }

    @GetMapping("/{id}")
    public ApiResponse<ResumeDetailResponse> getResumeDetail(
            @AuthenticationPrincipal Long userId,
            @PathVariable("id") Long resumeId) {
        return ApiResponse.success(resumeService.getResumeDetail(userId, resumeId));
    }

    @GetMapping("/{id}/profile")
    public ApiResponse<ResumeProfileResponse> getResumeProfile(
            @AuthenticationPrincipal Long userId,
            @PathVariable("id") Long resumeId) {
        return ApiResponse.success(resumeService.getResumeProfile(userId, resumeId));
    }

    @PutMapping("/{id}/confirm")
    public ApiResponse<Void> confirmResume(
            @AuthenticationPrincipal Long userId,
            @PathVariable("id") Long resumeId,
            @Valid @RequestBody ConfirmResumeRequest request) {
        resumeService.confirmResume(userId, resumeId, request.getProfile());
        return ApiResponse.success();
    }

    @PutMapping("/{id}/reparse")
    public ApiResponse<ResumeUploadResponse> reparseResume(
            @AuthenticationPrincipal Long userId,
            @PathVariable("id") Long resumeId) {
        return ApiResponse.success(resumeService.reparseResume(userId, resumeId));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> deleteResume(
            @AuthenticationPrincipal Long userId,
            @PathVariable("id") Long resumeId) {
        resumeService.deleteResume(userId, resumeId);
        return ApiResponse.success();
    }
}
