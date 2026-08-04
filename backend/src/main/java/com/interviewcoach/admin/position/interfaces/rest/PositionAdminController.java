package com.interviewcoach.admin.position.interfaces.rest;

import com.interviewcoach.common.response.ApiResponse;
import com.interviewcoach.position.application.dto.ConfirmPositionRequest;
import com.interviewcoach.position.application.dto.PositionAnalysisStatusResponse;
import com.interviewcoach.position.application.dto.PositionCreateRequest;
import com.interviewcoach.position.application.dto.PositionCreateResponse;
import com.interviewcoach.position.application.dto.PositionDetailResponse;
import com.interviewcoach.position.application.dto.PositionListResponse;
import com.interviewcoach.position.application.dto.PositionProfileResponse;
import com.interviewcoach.position.application.service.PositionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
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
 * 管理员公共岗位生命周期接口；所有最终查询再次限制目标必须为公共岗位。
 */
@RestController
@RequestMapping("/api/v1/admin/positions")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class PositionAdminController {

    private final PositionService positionService;

    @PostMapping
    public ApiResponse<PositionCreateResponse> createPosition(
            @AuthenticationPrincipal Long adminId,
            @Valid @RequestBody PositionCreateRequest request) {
        return ApiResponse.success(
                positionService.createPublicPosition(adminId, request));
    }

    @PostMapping("/upload")
    public ApiResponse<PositionCreateResponse> uploadPosition(
            @AuthenticationPrincipal Long adminId,
            @RequestPart("file") MultipartFile file,
            @RequestParam("fileType") String fileType,
            @RequestParam("positionName") String positionName) {
        return ApiResponse.success(positionService.uploadPublicPosition(
                adminId, file, fileType, positionName));
    }

    @GetMapping
    public ApiResponse<PositionListResponse> listPositions(
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "10") int size,
            @RequestParam(value = "archived", defaultValue = "false") boolean archived) {
        return ApiResponse.success(
                positionService.listPublicPositionsForAdmin(page, size, archived));
    }

    @GetMapping("/{id}")
    public ApiResponse<PositionDetailResponse> getPositionDetail(
            @PathVariable("id") Long positionId) {
        return ApiResponse.success(
                positionService.getPublicPositionDetailForAdmin(positionId));
    }

    @GetMapping("/{id}/profile")
    public ApiResponse<PositionProfileResponse> getPositionProfile(
            @PathVariable("id") Long positionId) {
        return ApiResponse.success(
                positionService.getPublicPositionProfileForAdmin(positionId));
    }

    @GetMapping("/{id}/analysis-status")
    public ApiResponse<PositionAnalysisStatusResponse> getAnalysisStatus(
            @PathVariable("id") Long positionId) {
        return ApiResponse.success(
                positionService.getPublicAnalysisStatusForAdmin(positionId));
    }

    @PutMapping("/{id}/confirm")
    public ApiResponse<Void> confirmPosition(
            @PathVariable("id") Long positionId,
            @Valid @RequestBody ConfirmPositionRequest request) {
        positionService.confirmPublicPosition(
                positionId, request.getTaskId(), request.getProfile());
        return ApiResponse.success();
    }

    @PutMapping("/{id}/reparse")
    public ApiResponse<PositionCreateResponse> reparsePosition(
            @AuthenticationPrincipal Long adminId,
            @PathVariable("id") Long positionId) {
        return ApiResponse.success(
                positionService.reparsePublicPosition(adminId, positionId));
    }

    @PutMapping("/{id}/archive")
    public ApiResponse<Void> archivePosition(@PathVariable("id") Long positionId) {
        positionService.archivePublicPosition(positionId);
        return ApiResponse.success();
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> deletePosition(@PathVariable("id") Long positionId) {
        positionService.deletePublicPosition(positionId);
        return ApiResponse.success();
    }
}
