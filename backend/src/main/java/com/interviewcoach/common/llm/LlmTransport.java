package com.interviewcoach.common.llm;

/**
 * 底层模型文本传输接口。
 *
 * <p>该接口不提供内容安全保证。迁移完成后，业务 Agent 只能调用统一安全入口，由安全入口在完成
 * 任务定义、DATA_ONLY 组装、风险检测和输出校验后调用本接口。</p>
 */
@FunctionalInterface
public interface LlmTransport {

    String chat(String systemPrompt, String userPrompt);
}
