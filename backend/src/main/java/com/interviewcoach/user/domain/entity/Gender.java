package com.interviewcoach.user.domain.entity;

/**
 * 用户扩展资料中保存和传输的性别稳定码。
 *
 * <p>资料更新接口按枚举名称解析并写入数据库，资料响应仍返回英文名称。
 * 当前枚举没有中文 {@code displayName}，API 也没有 {@code genderLabel}；该展示结构缺口不由注释解决。</p>
 */
public enum Gender {
    /**
     * 用户资料选择为男性，资料响应编码为 {@code MALE}。
     */
    MALE,

    /**
     * 用户资料选择为女性，资料响应编码为 {@code FEMALE}。
     */
    FEMALE,

    /**
     * 用户未明确选择性别时使用的默认资料状态，资料响应编码为 {@code UNKNOWN}。
     */
    UNKNOWN
}
