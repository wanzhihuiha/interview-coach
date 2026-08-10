package com.interviewcoach.common.llm;

import java.util.Objects;

/**
 * Prompt 风险检测器返回的定位信号，不包含命中的原始文本。
 *
 * @param blockId             命中的数据块 ID
 * @param riskType            风险类型
 * @param startCharacter      命中起点，按 Unicode 码点计数且包含该位置
 * @param endCharacter        命中终点，按 Unicode 码点计数且不包含该位置
 * @param confidence          检测置信等级
 * @param suggestedDisposition 建议的数据状态；最终业务动作不由检测器决定
 */
public record PromptRiskSignal(
        String blockId,
        RiskType riskType,
        int startCharacter,
        int endCharacter,
        Confidence confidence,
        SuggestedDisposition suggestedDisposition) {

    public PromptRiskSignal {
        if (blockId == null || blockId.isBlank()) {
            throw new IllegalArgumentException("Prompt 风险信号的数据块 ID 不能为空");
        }
        Objects.requireNonNull(riskType, "Prompt 风险类型不能为空");
        Objects.requireNonNull(confidence, "Prompt 风险置信等级不能为空");
        Objects.requireNonNull(suggestedDisposition, "Prompt 风险建议状态不能为空");
        if (startCharacter < 0 || endCharacter <= startCharacter) {
            throw new IllegalArgumentException("Prompt 风险信号位置无效");
        }
    }

    public enum RiskType {
        INSTRUCTION_OVERRIDE,
        ROLE_IMPERSONATION,
        SENSITIVE_DATA_EXFILTRATION,
        TOOL_OR_HIGH_RISK_ACTION,
        ENCODED_INSTRUCTION,
        CONTROL_CHARACTER,
        ZERO_WIDTH_CHARACTER,
        BIDIRECTIONAL_CONTROL,
        COMPATIBILITY_OBFUSCATION
    }

    public enum Confidence {
        LOW,
        MEDIUM,
        HIGH
    }

    public enum SuggestedDisposition {
        SUSPICIOUS,
        QUARANTINED
    }
}
