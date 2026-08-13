package com.interviewcoach.growth.domain.agent;

/**
 * 教练组件使用的岗位类别编码和兼容归一化规则。
 *
 * <p>成长服务把面试创建时保存的岗位类别传入本工具；教练组件据此选择固定任务模板、
 * 练习类型和资源表。这里不负责生成面向用户的中文 Label，也不会改写数据库中的岗位类别。</p>
 */
public final class JobCategory {

    /** 工具类不承载实例状态，禁止创建对象。 */
    private JobCategory() {
    }

    /** 软件开发、测试、运维、算法等技术岗位使用的稳定英文编码。 */
    public static final String TECH = "TECH";

    /** 产品规划、需求分析等产品岗位使用的稳定英文编码。 */
    public static final String PRODUCT = "PRODUCT";

    /** 视觉、交互和用户体验设计岗位使用的稳定英文编码。 */
    public static final String DESIGN = "DESIGN";

    /** 用户、内容、活动和增长运营岗位使用的稳定英文编码。 */
    public static final String OPERATION = "OPERATION";

    /** 销售、商务拓展和客户关系岗位使用的稳定英文编码。 */
    public static final String SALES = "SALES";

    /** 输入为空或无法匹配专门类别时使用的通用稳定英文编码。 */
    public static final String GENERAL = "GENERAL";

    /**
     * 将原始岗位类别归一化为教练组件支持的六种编码。
     *
     * <p>先对去除首尾空白并转为大写的完整输入做精确编码匹配；未命中时再按技术、产品、
     * 设计、运营、销售的固定顺序检查原始文本关键词，首个命中的类别生效，最终仍未命中则
     * 返回 {@link #GENERAL}。关键词集合与优先级的产品依据当前缺失，调整会改变模板和资源选择。</p>
     *
     * @param raw 面试岗位快照中的类别编码或可供兼容识别的岗位描述
     * @return 教练组件支持的稳定英文类别编码
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
                // 完整编码未命中时按固定类别顺序做兼容关键词识别，首个命中即结束判断。
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
     * 判断输入是否与技术类稳定编码相同，比较时忽略英文大小写；null 返回 {@code false}。
     */
    public static boolean isTechnical(String category) {
        return TECH.equalsIgnoreCase(category);
    }

    /** 按参数顺序判断文本是否包含任一固定关键词，不执行分词或边界匹配。 */
    private static boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }
}
