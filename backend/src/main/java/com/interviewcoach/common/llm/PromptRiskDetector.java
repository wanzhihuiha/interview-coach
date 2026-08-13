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

    /**
     * 检查数据块并返回不含命中原文的风险信号列表。
     *
     * <p>实现必须返回非 {@code null} 列表；实现抛出异常或返回 {@code null} 时，
     * 安全网关按失败关闭处理，不继续调用模型或采用模型结果。</p>
     *
     * @param dataBlocks 待进入模型或来自模型输出的 DATA_ONLY 数据块
     * @return 不含原始敏感文本的风险信号，可为空但不能为 {@code null}
     */
    List<PromptRiskSignal> detect(List<LlmDataBlock> dataBlocks);
}
