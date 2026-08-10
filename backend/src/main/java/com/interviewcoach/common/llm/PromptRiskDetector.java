package com.interviewcoach.common.llm;

import java.util.List;

/**
 * 可插拔 Prompt 风险信号源。
 *
 * <p>实现只能报告风险信号，不能选择 system prompt、模型、权限或业务动作。需要加入默认 Spring
 * 检测链的实现应使用 {@link PromptRiskDetectorChain#DELEGATE_QUALIFIER} 作为 Bean qualifier。</p>
 */
@FunctionalInterface
public interface PromptRiskDetector {

    List<PromptRiskSignal> detect(List<LlmDataBlock> dataBlocks);
}
