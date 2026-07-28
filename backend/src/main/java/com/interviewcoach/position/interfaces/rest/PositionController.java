package com.interviewcoach.position.interfaces.rest;

import com.interviewcoach.common.response.ApiResponse;
import com.interviewcoach.position.application.dto.AuditPositionRequest;
import com.interviewcoach.position.application.dto.ConfirmPositionRequest;
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
 * 岗位模块 REST 接口。
 */
@RestController
@RequestMapping("/api/v1/positions")
@RequiredArgsConstructor
public class PositionController {

    private final PositionService positionService;

    @PostMapping
    public ApiResponse<PositionCreateResponse> createPosition(
            @AuthenticationPrincipal Long userId,
            @Valid @RequestBody PositionCreateRequest request) {
        return ApiResponse.success(positionService.createPosition(userId, request));
    }

    @PostMapping("/upload")
    public ApiResponse<PositionCreateResponse> uploadPosition(
            @AuthenticationPrincipal Long userId,
            @RequestPart("file") MultipartFile file,
            @RequestParam("fileType") String fileType,
            @RequestParam("positionName") String positionName) {
        return ApiResponse.success(positionService.uploadPosition(userId, file, fileType, positionName));
    }

    @GetMapping
    public ApiResponse<PositionListResponse> listPositions(
            @AuthenticationPrincipal Long userId,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "10") int size,
            @RequestParam(value = "parseStatus", required = false) String parseStatus,
            @RequestParam(value = "auditStatus", required = false) String auditStatus) {
        return ApiResponse.success(positionService.listPositions(userId, page, size, parseStatus, auditStatus));
    }

    /**
     * 查询所有已审核通过的公共岗位，供所有登录用户选择。
     */
    @GetMapping("/public")
    public ApiResponse<PositionListResponse> listPublicPositions(
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "10") int size) {
        return ApiResponse.success(positionService.listPublicPositions(page, size));
    }

    @GetMapping("/{id}")
    public ApiResponse<PositionDetailResponse> getPositionDetail(
            @AuthenticationPrincipal Long userId,
            @PathVariable("id") Long positionId) {
        return ApiResponse.success(positionService.getPositionDetail(userId, positionId));
    }

    @GetMapping("/{id}/profile")
    public ApiResponse<PositionProfileResponse> getPositionProfile(
            @AuthenticationPrincipal Long userId,
            @PathVariable("id") Long positionId) {
        return ApiResponse.success(positionService.getPositionProfile(userId, positionId));
    }

    @PutMapping("/{id}/confirm")
    public ApiResponse<Void> confirmPosition(
            @AuthenticationPrincipal Long userId,
            @PathVariable("id") Long positionId,
            @Valid @RequestBody ConfirmPositionRequest request) {
        positionService.confirmPosition(userId, positionId, request.getProfile());
        return ApiResponse.success();
    }

    @PutMapping("/{id}/reparse")
    public ApiResponse<PositionCreateResponse> reparsePosition(
            @AuthenticationPrincipal Long userId,
            @PathVariable("id") Long positionId) {
        return ApiResponse.success(positionService.reparsePosition(userId, positionId));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> deletePosition(
            @AuthenticationPrincipal Long userId,
            @PathVariable("id") Long positionId) {
        positionService.deletePosition(userId, positionId);
        return ApiResponse.success();
    }

    @PutMapping("/{id}/audit")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<Void> auditPosition(
            @AuthenticationPrincipal Long auditorId,
            @PathVariable("id") Long positionId,
            @Valid @RequestBody AuditPositionRequest request) {
        positionService.auditPosition(positionId, request, auditorId);
        return ApiResponse.success();
    }
}
