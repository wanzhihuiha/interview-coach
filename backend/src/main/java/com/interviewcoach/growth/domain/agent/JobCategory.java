package com.interviewcoach.growth.domain.agent;

/**
 * 岗位大类常量，用于 CoachAgent 按岗位类型生成成长方案。
 */
public final class JobCategory {

    private JobCategory() {
    }

    /** 技术类。 */
    public static final String TECH = "TECH";

    /** 产品类。 */
    public static final String PRODUCT = "PRODUCT";

    /** 设计类。 */
    public static final String DESIGN = "DESIGN";

    /** 运营类。 */
    public static final String OPERATION = "OPERATION";

    /** 销售类。 */
    public static final String SALES = "SALES";

    /** 通用/未识别。 */
    public static final String GENERAL = "GENERAL";

    /**
     * 将原始岗位类别归一化为标准类别。
     */
    public static String normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            return GENERAL;
        }
        String upper = raw.trim().toUpperCase();
        return switch (upper) {
            case TECH -> TECH;
            case PRODUCT -> PRODUCT;
            case DESIGN -> DESIGN;
            case OPERATION -> OPERATION;
            case SALES -> SALES;
            default -> {
                // 兜底：根据常见关键词推断
                String lower = raw.toLowerCase();
                if (containsAny(lower, "java", "python", "go", "后端", "前端", "算法", "开发", "架构", "运维", "测试", "大数据", "ai", "人工智能")) {
                    yield TECH;
                }
                if (containsAny(lower, "产品", "产品经理", "prd")) {
                    yield PRODUCT;
                }
                if (containsAny(lower, "设计", "ui", "ux", "视觉", "交互")) {
                    yield DESIGN;
                }
                if (containsAny(lower, "运营", "增长", "活动", "内容", "新媒体")) {
                    yield OPERATION;
                }
                if (containsAny(lower, "销售", "商务", "客户", "bd")) {
                    yield SALES;
                }
                yield GENERAL;
            }
        };
    }

    /**
     * 判断是否为技术类岗位。
     */
    public static boolean isTechnical(String category) {
        return TECH.equalsIgnoreCase(category);
    }

    private static boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }
}
