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
 * 用户管理后台服务。
 */
@Service
@RequiredArgsConstructor
public class UserAdminService {

    private final UserRepository userRepository;

    /**
     * 分页查询所有用户。
     */
    @Transactional(readOnly = true)
    public UserAdminListResponse listUsers(int page, int size) {
        Pageable pageable = PageRequest.of(page, size);
        Page<User> userPage = userRepository.findAll(pageable);

        UserAdminListResponse response = new UserAdminListResponse();
        response.setContent(userPage.getContent().stream().map(this::toItem).toList());
        response.setTotalElements(userPage.getTotalElements());
        response.setTotalPages(userPage.getTotalPages());
        response.setCurrentPage(userPage.getNumber());
        return response;
    }

    /**
     * 更新用户状态（启用/禁用）。
     */
    @Transactional
    public void updateUserStatus(Long userId, UpdateUserStatusRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(USER_NOT_FOUND, "用户不存在"));
        UserStatus targetStatus = parseStatus(request.getStatus());
        user.setStatus(targetStatus);
        userRepository.save(user);
    }

    private UserStatus parseStatus(String status) {
        try {
            return UserStatus.valueOf(status.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(UserErrorCode.USER_STATUS_INVALID, "用户状态无效: " + status);
        }
    }

    private UserAdminListItemResponse toItem(User user) {
        UserAdminListItemResponse item = new UserAdminListItemResponse();
        item.setUserId(user.getId());
        item.setUsername(user.getUsername());
        item.setPhone(user.getPhone());
        item.setEmail(user.getEmail());
        item.setStatus(user.getStatus() != null ? user.getStatus().name() : null);
        item.setStatusLabel(user.getStatus() != null ? user.getStatus().getDisplayName() : null);
        var roles = user.getRoleList();
        item.setRoles(roles);
        item.setRoleLabels(roles.stream().map(UserRole::displayNameOf).toList());
        item.setCreateTime(user.getCreateTime());
        item.setUpdateTime(user.getUpdateTime());
        return item;
    }
}
