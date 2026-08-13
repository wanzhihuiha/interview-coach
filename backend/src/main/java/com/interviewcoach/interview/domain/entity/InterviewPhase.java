package com.interviewcoach.interview.domain.entity;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * 用户可选择并由服务端按固定顺序推进的面试环节。
 *
 * <p>稳定英文枚举名写入会话、消息和 API 编码；{@link #displayName} 作为现有 API 中文 Label，
 * {@link #order} 决定用户选择的实际顺序。</p>
 */
public enum InterviewPhase {
    /** 固定单题、不评估，回答后直接进入下一环节。 */
    SELF_INTRO("自我介绍", 1),

    /** 按岗位主题和 L1～L5 深度递进提问的专业环节。 */
    PROFESSIONAL("专业面试", 2),

    /** 围绕创建时简历项目快照逐项核验的环节。 */
    RESUME_DISCUSSION("简历探讨", 3),

    /** 按服务端固定场景生成 STAR 风格问题的环节。 */
    BEHAVIORAL("行为面试", 4),

    /** 生成结束语并把自然推进会话置为已结束的终止环节。 */
    ENDING("结束", 5);

    /** 当前环节返回给 API 和前端展示的中文名称。 */
    private final String displayName;

    /** 当前环节在固定流程中的升序位置。 */
    private final int order;

    /** 绑定中文展示名和固定顺序。 */
    InterviewPhase(String displayName, int order) {
        this.displayName = displayName;
        this.order = order;
    }

    /** 返回 API 中文 Label 使用的展示名称。 */
    public String getDisplayName() {
        return displayName;
    }

    /** 返回环节排序和完成状态推导使用的固定顺序。 */
    public int getOrder() {
        return order;
    }

    /**
     * 将环节编码转换为中文；未知值保持原样以兼容历史题库数据。
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

    /**
     * 按固定顺序对输入环节排序、追加 ENDING，再通过 {@code distinct} 保留首个重复值。
     * 当前创建应用服务另有等价整理逻辑，并未调用本方法。
     */
    public static List<InterviewPhase> sortSelected(List<InterviewPhase> selected) {
        return Stream.concat(
                selected.stream().sorted(Comparator.comparingInt(InterviewPhase::getOrder)),
                Stream.of(ENDING)
        ).distinct().toList();
    }
}
