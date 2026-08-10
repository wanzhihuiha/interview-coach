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
     * 向 LLM 发送 prompt 并返回原始文本响应。
     *
     * @param systemPrompt 系统提示词
     * @param userPrompt   用户提示词
     * @return LLM 原始响应文本
     */
    @Override
    String chat(String systemPrompt, String userPrompt);
}
