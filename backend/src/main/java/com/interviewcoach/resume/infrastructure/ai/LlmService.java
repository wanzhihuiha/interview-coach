package com.interviewcoach.resume.infrastructure.ai;

/**
 * LLM 服务统一入口，负责与底层大模型交互。
 */
public interface LlmService {

    /**
     * 向 LLM 发送 prompt 并返回原始文本响应。
     *
     * @param systemPrompt 系统提示词
     * @param userPrompt   用户提示词
     * @return LLM 原始响应文本
     */
    String chat(String systemPrompt, String userPrompt);
}
