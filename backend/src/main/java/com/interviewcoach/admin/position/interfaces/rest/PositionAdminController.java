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
 * 管理员维护公共岗位完整生命周期的 HTTP 入口。
 *
 * <p>类级权限表达式要求当前 JWT 安全上下文包含 ADMIN 角色。该入口覆盖创建、上传、列表、
 * 详情、画像、解析状态、确认、重解析、归档和永久删除；下层服务和仓储会再次限制目标必须为
 * 公共岗位，管理权限不允许读取或修改个人岗位。</p>
 */
@RestController
@RequestMapping("/api/v1/admin/positions")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class PositionAdminController {

    /**
     * 执行公共岗位提交、查询、解析确认和生命周期状态变更。
     */
    private final PositionService positionService;

    /**
     * 通过结构化 JD 创建公共岗位并登记当前解析任务。
     *
     * <p>{@code adminId} 来自 JWT 建立的服务端安全上下文，不从请求体采信身份。请求 DTO
     * 先通过 Bean Validation，随后服务规范化 JD 并在公共提交锁内检查等待上限；校验、
     * 容量或持久化失败时异常沿统一处理链路返回，不产生成功响应。</p>
     *
     * @param adminId 当前已认证管理员的用户 ID
     * @param request 岗位名称、类别、JD 及可选岗位信息
     * @return 新公共岗位 ID、名称及其当前解析任务信息
     */
    @PostMapping
    public ApiResponse<PositionCreateResponse> createPosition(
            @AuthenticationPrincipal Long adminId,
            @Valid @RequestBody PositionCreateRequest request) {
        // 使用可信管理员身份登记公共岗位和 WAITING 任务，成功后把提交结果包装为管理端响应。
        return ApiResponse.success(
                positionService.createPublicPosition(adminId, request));
    }

    /**
     * 从上传文件提取 JD 文本后创建公共岗位并登记解析任务。
     *
     * <p>{@code adminId} 来自 JWT 安全上下文。服务在事务外校验岗位名称并提取文件，
     * 只有得到有效文本后才进入公共提交锁和数据库事务；文件类型、内容、容量或存储失败时
     * 异常由统一处理链路返回。</p>
     *
     * @param adminId      当前已认证管理员的用户 ID
     * @param file         待提取 JD 的上传文件
     * @param fileType     客户端提交的文件类型编码
     * @param positionName 公共岗位名称
     * @return 新公共岗位及其当前解析任务信息
     */
    @PostMapping("/upload")
    public ApiResponse<PositionCreateResponse> uploadPosition(
            @AuthenticationPrincipal Long adminId,
            @RequestPart("file") MultipartFile file,
            @RequestParam("fileType") String fileType,
            @RequestParam("positionName") String positionName) {
        // 先由服务提取并校验文件内容，再使用可信管理员身份提交公共岗位解析任务。
        return ApiResponse.success(positionService.uploadPublicPosition(
                adminId, file, fileType, positionName));
    }

    /**
     * 分页查询公共岗位的活动视图或归档视图。
     *
     * <p>页码使用零基约定，{@code archived=false} 查询未归档公共岗位，{@code true}
     * 查询已归档公共岗位。当前默认每页 10 条的精确产品依据缺失；调大该值会增加批量任务、
     * 画像读取以及 DTO 映射和响应数据量，调小则会增加总页数和翻页请求。非法分页值由下层
     * 分页构造拒绝。</p>
     *
     * @param page     零基页码，默认 {@code 0}
     * @param size     每页记录数，当前默认 {@code 10}，精确取值依据缺失
     * @param archived 是否查询归档视图
     * @return 统一响应包装的公共岗位分页数据
     */
    @GetMapping
    public ApiResponse<PositionListResponse> listPositions(
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "10") int size,
            @RequestParam(value = "archived", defaultValue = "false") boolean archived) {
        // 服务按归档标记选择公共岗位仓储查询，并再次限制 isPublic=true。
        return ApiResponse.success(
                positionService.listPublicPositionsForAdmin(page, size, archived));
    }

    /**
     * 查询指定公共岗位的管理详情。
     *
     * <p>服务按“主键且公共岗位”读取，因此活动和已归档公共岗位均可返回，个人岗位与不存在的
     * ID 统一进入岗位不存在的业务异常路径。</p>
     *
     * @param positionId 目标公共岗位 ID
     * @return 包含岗位正文、归档信息和当前解析摘要的管理详情
     */
    @GetMapping("/{id}")
    public ApiResponse<PositionDetailResponse> getPositionDetail(
            @PathVariable("id") Long positionId) {
        // 通过公共岗位专用查询读取详情，避免 ADMIN 入口越界读取个人岗位。
        return ApiResponse.success(
                positionService.getPublicPositionDetailForAdmin(positionId));
    }

    /**
     * 查询指定公共岗位的正式画像和当前候选画像。
     *
     * <p>管理视图可以查看待确认候选；服务仍按公共岗位约束读取。个人岗位或不存在的 ID
     * 进入岗位不存在的业务异常路径，损坏的画像数据进入画像数据异常路径。</p>
     *
     * @param positionId 目标公共岗位 ID
     * @return 正式画像、当前候选及其解析状态
     */
    @GetMapping("/{id}/profile")
    public ApiResponse<PositionProfileResponse> getPositionProfile(
            @PathVariable("id") Long positionId) {
        // 使用管理员画像映射保留公共岗位候选，供后续确认接口提交当前 taskId。
        return ApiResponse.success(
                positionService.getPublicPositionProfileForAdmin(positionId));
    }

    /**
     * 查询指定公共岗位当前解析任务的轻量轮询状态。
     *
     * <p>状态服务只加载公共岗位，并返回当前任务、排队位置、错误摘要和画像可用性；
     * 个人岗位或不存在的 ID 进入岗位不存在的业务异常路径。</p>
     *
     * @param positionId 目标公共岗位 ID
     * @return 当前解析任务的轻量状态
     */
    @GetMapping("/{id}/analysis-status")
    public ApiResponse<PositionAnalysisStatusResponse> getAnalysisStatus(
            @PathVariable("id") Long positionId) {
        // 从公共岗位当前任务快照生成轮询响应，不读取个人岗位任务。
        return ApiResponse.success(
                positionService.getPublicAnalysisStatusForAdmin(positionId));
    }

    /**
     * 确认当前公共岗位解析候选并写入正式画像。
     *
     * <p>请求必须携带当前成功任务的 {@code taskId} 和合法画像。服务锁定公共岗位及其当前任务，
     * 校验岗位未归档且任务仍为 SUCCEEDED 后保存正式画像、在画像提供级别时同步岗位级别，
     * 并删除已确认任务；岗位不存在、已归档或任务已变化时抛出业务异常，事务不会提交部分
     * 结果。</p>
     *
     * @param positionId 目标公共岗位 ID
     * @param request    当前任务 ID 和管理员确认后的画像
     * @return 无业务数据的成功响应
     */
    @PutMapping("/{id}/confirm")
    public ApiResponse<Void> confirmPosition(
            @PathVariable("id") Long positionId,
            @Valid @RequestBody ConfirmPositionRequest request) {
        // 以请求中的当前 taskId 作为并发守卫，在同一事务内落正式画像并清除已确认任务。
        positionService.confirmPublicPosition(
                positionId, request.getTaskId(), request.getProfile());
        return ApiResponse.success();
    }

    /**
     * 为活动公共岗位登记一次新的解析任务。
     *
     * <p>{@code adminId} 来自 JWT 安全上下文并记录为任务发起人。服务只接受公共且未归档岗位，
     * 当前任务不存在或已处于终态时才可替换；等待/运行中的任务、公共等待容量不足或资源不存在
     * 均进入业务异常路径。</p>
     *
     * @param adminId    当前已认证管理员的用户 ID
     * @param positionId 目标公共岗位 ID
     * @return 新登记的解析任务信息
     */
    @PutMapping("/{id}/reparse")
    public ApiResponse<PositionCreateResponse> reparsePosition(
            @AuthenticationPrincipal Long adminId,
            @PathVariable("id") Long positionId) {
        // 使用可信管理员身份在公共提交锁内替换终态任务，并返回新的 WAITING 任务。
        return ApiResponse.success(
                positionService.reparsePublicPosition(adminId, positionId));
    }

    /**
     * 归档指定公共岗位。
     *
     * <p>归档是幂等操作：已归档岗位再次调用直接成功。首次归档会阻止普通用户继续公开读取，
     * 并删除非运行的当前任务；RUNNING 任务保留到 Worker 结束。个人岗位或不存在的 ID
     * 进入岗位不存在的业务异常路径。</p>
     *
     * @param positionId 目标公共岗位 ID
     * @return 无业务数据的成功响应
     */
    @PutMapping("/{id}/archive")
    public ApiResponse<Void> archivePosition(@PathVariable("id") Long positionId) {
        // 服务在公共岗位行锁内写入归档时间，并按当前任务状态执行必要清理。
        positionService.archivePublicPosition(positionId);
        return ApiResponse.success();
    }

    /**
     * 永久删除指定公共岗位及其当前任务和正式画像。
     *
     * <p>只有已归档、没有 RUNNING 当前任务且没有进行中面试的公共岗位可以删除；删除流程不清理
     * 历史面试记录。任一守卫不满足或目标不是公共岗位时抛出业务异常，不返回成功响应。</p>
     *
     * @param positionId 目标公共岗位 ID
     * @return 无业务数据的成功响应
     */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> deletePosition(@PathVariable("id") Long positionId) {
        // 服务锁定公共岗位并通过归档、运行任务和进行中面试守卫后执行永久删除。
        positionService.deletePublicPosition(positionId);
        return ApiResponse.success();
    }
}
