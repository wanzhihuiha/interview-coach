package com.interviewcoach.common.llm;

import java.util.Objects;

/**
 * Prompt 风险检测器返回的定位信号，不包含命中的原始文本。
 *
 * @param blockId             命中的数据块 ID，用于调用方定位风险块而不暴露原文
 * @param riskType            检测到的风险类别
 * @param startCharacter      命中起点，按 Unicode 码点计数且包含该位置
 * @param endCharacter        命中终点，按 Unicode 码点计数且不包含该位置
 * @param confidence          规则对该信号的置信等级，不是业务允许或拒绝结论
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

    /**
     * 检测器能够报告的风险语义；枚举值只描述信号，不直接执行任何业务动作。
     */
    public enum RiskType {
        /** 试图覆盖、忽略或绕过服务端既有指令。 */
        INSTRUCTION_OVERRIDE,
        /** 试图把数据内容冒充为系统、开发者或管理员角色消息。 */
        ROLE_IMPERSONATION,
        /** 试图读取、展示或外发提示词、密钥、令牌等敏感信息。 */
        SENSITIVE_DATA_EXFILTRATION,
        /** 试图诱导工具调用、命令执行、数据删除或其他高风险动作。 */
        TOOL_OR_HIGH_RISK_ACTION,
        /** Base64 或十六进制一次解码后出现可见指令模式。 */
        ENCODED_INSTRUCTION,
        /** 出现换行、制表符和回车之外的 C0/C1 控制字符。 */
        CONTROL_CHARACTER,
        /** 出现当前规则识别的软连字符、零宽或字节序标记等不可见字符。 */
        ZERO_WIDTH_CHARACTER,
        /** 出现可能改变文本显示顺序的双向控制字符。 */
        BIDIRECTIONAL_CONTROL,
        /** NFKC 兼容归一化后才显现出指令模式。 */
        COMPATIBILITY_OBFUSCATION
    }

    /**
     * 检测规则对单个风险信号的置信等级，供调用方记录或选择处置策略。
     */
    public enum Confidence {
        /** 弱特征命中，误报可能性相对较高。 */
        LOW,
        /** 组合特征命中，但仍需要由调用方按整体策略处置。 */
        MEDIUM,
        /** 明确规则或控制字符命中，检测器认为风险特征较强。 */
        HIGH
    }

    /**
     * 检测器建议的数据状态；安全网关仍根据自身策略决定是否拒绝。
     */
    public enum SuggestedDisposition {
        /** 建议将数据标为可疑并避免直接信任。 */
        SUSPICIOUS,
        /** 建议隔离该数据，不让其继续进入模型或业务结果。 */
        QUARANTINED
    }
}
