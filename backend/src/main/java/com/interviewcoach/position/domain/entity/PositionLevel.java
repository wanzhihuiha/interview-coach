package com.interviewcoach.position.domain.entity;

import java.util.Locale;

/**
 * 岗位职级编码及中文名称。
 */
public enum PositionLevel {
    /** 初级岗位职级，用于当前编码到中文名称的展示映射。 */
    JUNIOR("初级"),
    /** 中级岗位职级，用于当前编码到中文名称的展示映射。 */
    MID("中级"),
    /** 高级岗位职级，用于当前编码到中文名称的展示映射。 */
    SENIOR("高级"),
    /** 专家岗位职级，用于当前编码到中文名称的展示映射。 */
    EXPERT("专家");

    /** 当前职级编码对应的中文展示名称。 */
    private final String displayName;

    PositionLevel(String displayName) {
        this.displayName = displayName;
    }

    /** 返回当前职级编码的中文展示名称。 */
    public String getDisplayName() {
        return displayName;
    }

    /**
     * 将已知英文职级编码转换为中文；模型返回的中文或未知历史值保持原样，空白输入返回空值。
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
