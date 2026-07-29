package com.interviewcoach.common.domain;

import java.util.Locale;

/**
 * 跨业务域使用的岗位类别编码及中文名称。
 */
public enum JobCategoryType {
    TECH("技术类"),
    PRODUCT("产品类"),
    DESIGN("设计类"),
    OPERATION("运营类"),
    SALES("销售类"),
    GENERAL("通用类");

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
