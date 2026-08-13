package com.interviewcoach.common.llm;

/**
 * 底层模型文本传输接口。
 *
 * <p>该接口不提供内容安全保证。迁移完成后，业务 Agent 只能调用统一安全入口，由安全入口在完成
 * 任务定义、DATA_ONLY 组装、风险检测和输出校验后调用本接口。</p>
 */
@FunctionalInterface
public interface LlmTransport {

    /**
     * 把安全网关组装的 system prompt 和 DATA_ONLY user prompt 发送到底层模型。
     *
     * @param systemPrompt 仅由服务端任务定义和固定安全策略构成的系统提示词
     * @param userPrompt 由网关 JSON 序列化的不可信数据消息
     * @return 供应商返回的原始文本，仅可交回安全网关解析和复检
     * @throws LlmTransportException 适配器已归类的传输失败
     * @throws RuntimeException 适配器尚未归类的运行时失败，由安全网关收敛为稳定失败类型
     */
    String chat(String systemPrompt, String userPrompt);
}
