package com.interviewcoach.admin.user.application.service;

import static com.interviewcoach.user.application.service.UserErrorCode.USER_NOT_FOUND;

import com.interviewcoach.admin.user.application.dto.UpdateUserStatusRequest;
import com.interviewcoach.admin.user.application.dto.UserAdminListItemResponse;
import com.interviewcoach.admin.user.application.dto.UserAdminListResponse;
import com.interviewcoach.common.exception.BusinessException;
import com.interviewcoach.user.application.service.UserErrorCode;
import com.interviewcoach.user.domain.entity.User;
import com.interviewcoach.user.domain.entity.UserRole;
import com.interviewcoach.user.domain.entity.UserStatus;
import com.interviewcoach.user.domain.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 管理端用户查询和账号状态更新服务。
 *
 * <p>由受 ADMIN 角色保护的 HTTP 入口调用，负责全量用户分页、管理 DTO 映射以及 ACTIVE、
 * DISABLED 状态写入；本服务自身不重复校验管理员角色，手机号和邮箱也不在映射时脱敏。</p>
 */
@Service
@RequiredArgsConstructor
public class UserAdminService {

    /**
     * 提供用户表全量分页、目标账号查找和状态持久化。
     */
    private final UserRepository userRepository;

    /**
     * 分页查询用户表中的全部账号。
     *
     * <p>页码使用零基约定，当前查询不附加账号状态、角色过滤或显式排序。响应同时返回
     * 状态和角色的稳定编码及中文 Label，并保留用户表中的手机号、邮箱原值。</p>
     *
     * @param page 零基页码
     * @param size 每页记录数
     * @return 当前页管理端用户项及仓储分页元数据
     */
    @Transactional(readOnly = true)
    public UserAdminListResponse listUsers(int page, int size) {
        // 将零基页码和页大小交给 Spring Data；非法分页值由 PageRequest 直接拒绝。
        Pageable pageable = PageRequest.of(page, size);
        // 读取用户表全量分页，不附加状态、角色或资源归属条件。
        Page<User> userPage = userRepository.findAll(pageable);

        UserAdminListResponse response = new UserAdminListResponse();
        // 将当前页实体映射为管理端 DTO，同时保留仓储计算的总数、总页数和实际页码。
        response.setContent(userPage.getContent().stream().map(this::toItem).toList());
        response.setTotalElements(userPage.getTotalElements());
        response.setTotalPages(userPage.getTotalPages());
        response.setCurrentPage(userPage.getNumber());
        return response;
    }

    /**
     * 将目标账号状态更新为 ACTIVE 或 DISABLED。
     *
     * <p>管理员角色由 HTTP 入口保证。本事务先按用户 ID 加载实体，再把请求编码统一为大写并
     * 解析为状态枚举，最后保存实体；用户不存在返回 2001，未知非空状态返回 6001，
     * 任一异常都会阻止本次状态写入提交。</p>
     *
     * @param userId  目标账号 ID
     * @param request 已通过入口非空校验的目标状态请求
     */
    @Transactional
    public void updateUserStatus(Long userId, UpdateUserStatusRequest request) {
        // 先确认目标用户存在，不存在时以用户模块 2001 业务错误结束。
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(USER_NOT_FOUND, "用户不存在"));
        // 将管理端状态编码解析为当前受支持枚举，未知编码以 6001 拒绝。
        UserStatus targetStatus = parseStatus(request.getStatus());
        user.setStatus(targetStatus);
        // 在当前事务内保存状态；持久化异常会回滚本次修改。
        userRepository.save(user);
    }

    /**
     * 将已通过入口非空校验的状态编码转换为枚举；未知编码统一映射为用户业务错误码 6001。
     */
    private UserStatus parseStatus(String status) {
        try {
            return UserStatus.valueOf(status.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(UserErrorCode.USER_STATUS_INVALID, "用户状态无效: " + status);
        }
    }

    /**
     * 将用户实体映射为管理端列表项。
     *
     * <p>手机号和邮箱按数据库原值返回；角色编码由实体按逗号顺序解析，中文名称使用相同顺序
     * 映射，因此 {@code roles} 与 {@code roleLabels} 可以按索引对应，未知历史角色保留原值。</p>
     */
    private UserAdminListItemResponse toItem(User user) {
        UserAdminListItemResponse item = new UserAdminListItemResponse();
        item.setUserId(user.getId());
        item.setUsername(user.getUsername());
        item.setPhone(user.getPhone());
        item.setEmail(user.getEmail());
        // 状态同时输出稳定编码和中文名称，实体状态为空时两者都保持为空。
        item.setStatus(user.getStatus() != null ? user.getStatus().name() : null);
        item.setStatusLabel(user.getStatus() != null ? user.getStatus().getDisplayName() : null);
        // 先按实体原有顺序解析角色编码，再用同一列表顺序生成逐项对应的中文名称。
        var roles = user.getRoleList();
        item.setRoles(roles);
        item.setRoleLabels(roles.stream().map(UserRole::displayNameOf).toList());
        item.setCreateTime(user.getCreateTime());
        item.setUpdateTime(user.getUpdateTime());
        return item;
    }
}
