package com.interviewcoach.resume.domain.entity;

import java.util.Locale;

/**
 * 正式事实画像中的经验等级，由规范化服务根据工作年限文本或经历时长确定性推导，并供接口展示及面试选题使用。
 */
public enum ExperienceLevel {
    /** 确定性解析得到不足 3 年；没有可解析年限时当前也按 0 年归入此值，3 年阈值依据缺失。 */
    JUNIOR("初级"),
    /** 明确工作年限为 3 年及以上且不足 7 年；3/7 年阈值的精确依据缺失。 */
    MID("中级"),
    /** 明确工作年限为 7 年及以上；7 年阈值的精确依据缺失。 */
    SENIOR("高级");

    /** 面向接口展示的中文名称。 */
    private final String displayName;

    /** 绑定稳定英文枚举值与中文展示名。 */
    ExperienceLevel(String displayName) {
        this.displayName = displayName;
    }

    /** 返回当前经验等级的中文展示名。 */
    public String getDisplayName() {
        return displayName;
    }

    /**
     * 将经验等级编码转换为中文；未知值保持原样以兼容历史数据。
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
