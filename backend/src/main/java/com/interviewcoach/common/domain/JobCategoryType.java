package com.interviewcoach.common.domain;

import java.util.Locale;

/**
 * 跨简历、岗位和题库业务使用的岗位类别编码与中文展示名称。
 *
 * <p>枚举名是持久化和接口中的英文类别编码，{@link #displayName} 供响应 DTO 生成中文 Label；
 * {@link #displayNameOf(String)} 对未知历史编码保持原值，避免展示转换丢失信息。</p>
 */
public enum JobCategoryType {
    /**
     * 软件开发、测试、运维及其他技术岗位。
     */
    TECH("技术类"),

    /**
     * 产品规划、需求分析及产品运营协作岗位。
     */
    PRODUCT("产品类"),

    /**
     * 视觉、交互及其他设计岗位。
     */
    DESIGN("设计类"),

    /**
     * 内容、用户、活动及其他运营岗位。
     */
    OPERATION("运营类"),

    /**
     * 客户拓展、商务转化及其他销售岗位。
     */
    SALES("销售类"),

    /**
     * 无法归入上述专门类别或需要跨类别处理的通用岗位。
     */
    GENERAL("通用类");

    /**
     * 与当前英文枚举编码配套的中文展示名称，供接口响应中的类别 Label 使用。
     */
    private final String displayName;

    JobCategoryType(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }

    /**
     * 将稳定编码转换为中文；未知的历史值保持原样，避免丢失业务信息。
     */
    public static String displayNameOf(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT)).getDisplayName();
        } catch (IllegalArgumentException e) {
            return value;
        }
    }
}
