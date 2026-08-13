package com.interviewcoach.user.application.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 预留的注册账号摘要响应。
 *
 * <p>当前主源码没有使用本类型；实际注册接口返回包含 JWT 和用户摘要的 {@link LoginResponse}。
 * 本类仅描述现存结构，不代表当前 HTTP 响应契约。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RegisterResponse {

    /**
     * 预留的注册用户主键，当前注册主流程不会构造本类型。
     */
    private Long userId;

    /**
     * 预留的注册用户名，当前注册主流程不会构造本类型。
     */
    private String username;
}
