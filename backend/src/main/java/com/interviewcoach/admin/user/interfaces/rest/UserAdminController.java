package com.interviewcoach.admin.user.interfaces.rest;

import com.interviewcoach.admin.user.application.dto.UpdateUserStatusRequest;
import com.interviewcoach.admin.user.application.dto.UserAdminListResponse;
import com.interviewcoach.admin.user.application.service.UserAdminService;
import com.interviewcoach.common.response.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理员查看账号列表和启用、禁用账号的 HTTP 入口。
 *
 * <p>类级权限表达式要求当前 JWT 安全上下文包含 ADMIN 角色，操作者身份不从请求参数采信；
 * Controller 只接收分页或目标状态，数据库查询、状态解析和写入由用户管理服务完成。</p>
 */
@RestController
@RequestMapping("/api/v1/admin/users")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class UserAdminController {

    /**
     * 执行用户表分页查询和目标账号状态写入。
     */
    private final UserAdminService userAdminService;

    /**
     * 分页查询全部用户账号。
     *
     * <p>页码使用零基约定，当前默认每页 20 条的精确产品依据缺失；调大该值会增加单次
     * 用户表读取、敏感联系方式映射和响应数据量，调小则会增加总页数和翻页请求。服务不附加
     * 状态、角色过滤或显式排序。</p>
     *
     * @param page 零基页码，默认 {@code 0}
     * @param size 每页记录数，当前默认 {@code 20}，精确取值依据缺失
     * @return 统一响应包装的用户账号分页数据
     */
    @GetMapping
    public ApiResponse<UserAdminListResponse> list(
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "20") int size) {
        // 将管理员分页请求交给服务读取全部用户；非法分页值由下层分页构造拒绝。
        return ApiResponse.success(userAdminService.listUsers(page, size));
    }

    /**
     * 将目标账号状态更新为 ACTIVE 或 DISABLED。
     *
     * <p>请求状态先通过非空校验，服务随后统一为大写并解析稳定编码。目标用户不存在时返回
     * 2001，未知非空状态返回 6001；保存失败时异常沿统一处理链路返回，不产生成功响应。</p>
     *
     * @param userId  目标账号 ID，不能作为管理员操作者身份
     * @param request 目标账号状态请求
     * @return 无业务数据的成功响应
     */
    @PutMapping("/{id}/status")
    public ApiResponse<Void> updateStatus(
            @PathVariable("id") Long userId,
            @Valid @RequestBody UpdateUserStatusRequest request) {
        // 在 ADMIN 入口鉴权通过后，由服务查找目标账号、解析状态并在事务内保存。
        userAdminService.updateUserStatus(userId, request);
        return ApiResponse.success();
    }
}
