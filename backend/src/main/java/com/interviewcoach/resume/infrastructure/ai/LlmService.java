package com.interviewcoach.resume.infrastructure.ai;

import com.interviewcoach.common.llm.LlmTransport;

/**
 * 旧版 LLM 服务入口，负责与底层大模型交互。
 *
 * <p>该接口暂时保留现有 Agent 的兼容性；新安全入口依赖 {@link LlmTransport}，待所有调用方迁移后
 * 再移除本接口。</p>
 */
@FunctionalInterface
public interface LlmService extends LlmTransport {

    /**
     * 把系统提示词和可能包含脱敏业务数据的用户提示词交给当前 Mock 或真实供应商，并返回原始文本。
     *
     * @param systemPrompt 服务端构造的系统提示词
     * @param userPrompt Agent 构造的用户提示词；真实实现会发送给外部模型供应商
     * @return 供应商或 Mock 的原始响应；空响应和异常由调用方按各自任务契约处理
     */
    @Override
    String chat(String systemPrompt, String userPrompt);
}
