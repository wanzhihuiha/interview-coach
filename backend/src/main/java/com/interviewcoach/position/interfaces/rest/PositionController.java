package com.interviewcoach.position.interfaces.rest;

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
 * 个人岗位管理和已发布公共岗位只读 REST 接口。
 * 个人入口从 Spring Security 注入的可信用户 ID 发起用例，应用服务继续校验岗位归属；本 Controller 不提供公共岗位写能力。
 */
@RestController
@RequestMapping("/api/v1/positions")
@RequiredArgsConstructor
public class PositionController {

    /** 编排输入校验、资源归属、任务状态和岗位生命周期的应用服务。 */
    private final PositionService positionService;

    /** 使用粘贴 JD 为当前登录用户登记个人岗位和 WAITING 任务，业务异常交给统一异常处理。 */
    @PostMapping
    public ApiResponse<PositionCreateResponse> createPosition(
            @AuthenticationPrincipal Long userId,
            @Valid @RequestBody PositionCreateRequest request) {
        // 把可信登录用户和已完成传输校验的请求交给应用层，返回可继续轮询的岗位与任务标识。
        return ApiResponse.success(positionService.createPosition(userId, request));
    }

    /** 上传 PDF/TXT 并为当前登录用户登记个人岗位；文件提取或提交失败由统一异常契约返回。 */
    @PostMapping("/upload")
    public ApiResponse<PositionCreateResponse> uploadPosition(
            @AuthenticationPrincipal Long userId,
            @RequestPart("file") MultipartFile file,
            @RequestParam("fileType") String fileType,
            @RequestParam("positionName") String positionName) {
        // 应用层负责实际文件校验、临时文件清理和本人岗位登记，客户端类型与名称不作为授权依据。
        return ApiResponse.success(
                positionService.uploadPosition(userId, file, fileType, positionName));
    }

    /**
     * 分页查询当前登录用户的活动或归档个人岗位；默认零基第一页、每页 10 条，精确默认页大小依据缺失。
     */
    @GetMapping
    public ApiResponse<PositionListResponse> listPositions(
            @AuthenticationPrincipal Long userId,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "10") int size,
            @RequestParam(value = "archived", defaultValue = "false") boolean archived) {
        // 服务查询始终带可信用户归属和个人岗位条件，不返回公共或其他用户记录。
        return ApiResponse.success(
                positionService.listPositions(userId, page, size, archived));
    }

    /**
     * 查询已确认且未归档的公共岗位，普通用户没有任何公共写入口。
     * 默认零基第一页、每页 10 条，精确默认页大小依据缺失。
     */
    @GetMapping("/public")
    public ApiResponse<PositionListResponse> listPublicPositions(
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "10") int size) {
        // 应用层只查询正式画像可用的活动公共岗位，并隐藏候选、失败和任务细节。
        return ApiResponse.success(positionService.listPublicPositions(page, size));
    }

    /**
     * 首页组合本人和已发布公共岗位，只返回正式画像可用于面试的活动记录。
     * 默认零基第一页、每页 10 条，精确默认页大小依据缺失。
     */
    @GetMapping("/accessible")
    public ApiResponse<PositionListResponse> listAccessiblePositions(
            @AuthenticationPrincipal Long userId,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "10") int size) {
        // 服务端使用可信用户归属组合本人个人岗位和公共岗位，其他用户个人岗位不会进入结果。
        return ApiResponse.success(
                positionService.listAccessiblePositions(userId, page, size));
    }

    /** 查询本人个人岗位或已发布公共岗位详情；不满足归属或发布条件时按不存在处理。 */
    @GetMapping("/{id}")
    public ApiResponse<PositionDetailResponse> getPositionDetail(
            @AuthenticationPrincipal Long userId,
            @PathVariable("id") Long positionId) {
        // 应用层执行归属和公共可见性查询，并按调用场景隐藏不可见任务信息。
        return ApiResponse.success(positionService.getPositionDetail(userId, positionId));
    }

    /** 查询本人岗位的正式与候选画像，或只查询已发布公共岗位的正式画像。 */
    @GetMapping("/{id}/profile")
    public ApiResponse<PositionProfileResponse> getPositionProfile(
            @AuthenticationPrincipal Long userId,
            @PathVariable("id") Long positionId) {
        // 服务端根据资源可管理性决定是否暴露当前候选与任务，不采信请求中的归属信息。
        return ApiResponse.success(positionService.getPositionProfile(userId, positionId));
    }

    /** 轮询本人个人岗位的当前 MySQL 任务状态，并在 Redis 可用时附带非承诺的等待量估算。 */
    @GetMapping("/{id}/analysis-status")
    public ApiResponse<PositionAnalysisStatusResponse> getAnalysisStatus(
            @AuthenticationPrincipal Long userId,
            @PathVariable("id") Long positionId) {
        // 应用层只接受本人个人岗位；Redis 估算失败时仍保留 MySQL 状态响应。
        return ApiResponse.success(positionService.getAnalysisStatus(userId, positionId));
    }

    /** 使用精确当前任务 ID 确认本人岗位的成功候选，并将其写成正式画像。 */
    @PutMapping("/{id}/confirm")
    public ApiResponse<Void> confirmPosition(
            @AuthenticationPrincipal Long userId,
            @PathVariable("id") Long positionId,
            @Valid @RequestBody ConfirmPositionRequest request) {
        // 状态事务再次锁定本人岗位和当前任务，旧任务、归档岗位或非成功候选都会失败。
        positionService.confirmPosition(
                userId, positionId, request.getTaskId(), request.getProfile());
        return ApiResponse.success();
    }

    /** 为本人活动岗位以新 WAITING 任务替换无任务或已终结任务，并返回新轮询标识。 */
    @PutMapping("/{id}/reparse")
    public ApiResponse<PositionCreateResponse> reparsePosition(
            @AuthenticationPrincipal Long userId,
            @PathVariable("id") Long positionId) {
        // 应用层继续执行本人归属、任务源状态、等待上限和提交间隔守卫。
        return ApiResponse.success(positionService.reparsePosition(userId, positionId));
    }

    /** 幂等归档本人个人岗位，立即阻止新面试并按当前任务状态安排投影清理。 */
    @PutMapping("/{id}/archive")
    public ApiResponse<Void> archivePosition(
            @AuthenticationPrincipal Long userId,
            @PathVariable("id") Long positionId) {
        // 生命周期服务锁定本人资源并处理任务；归档不会删除正式画像或历史面试快照。
        positionService.archivePosition(userId, positionId);
        return ApiResponse.success();
    }

    /** 永久删除本人已归档且没有运行任务或进行中面试的岗位；不删除历史面试快照。 */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> deletePosition(
            @AuthenticationPrincipal Long userId,
            @PathVariable("id") Long positionId) {
        // 生命周期服务完成最终归属和删除守卫后，按任务、画像、岗位顺序删除当前记录。
        positionService.deletePosition(userId, positionId);
        return ApiResponse.success();
    }
}
