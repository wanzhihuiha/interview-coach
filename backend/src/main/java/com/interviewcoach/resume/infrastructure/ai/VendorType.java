package com.interviewcoach.resume.infrastructure.ai;

/**
 * LLM 厂商类型。
 */
public enum VendorType {
    /**
     * 阿里云 DashScope，使用 Spring AI Alibaba。
     */
    DASHSCOPE,
    /**
     * OpenAI 兼容接口，可用于 OpenAI、智谱、DeepSeek 等支持 /v1/chat/completions 的厂商。
     */
    OPENAI_COMPATIBLE,
    /**
     * Anthropic Messages API 兼容接口，可用于 MiniMax 订阅密钥（sk-cp-）等厂商。
     */
    ANTHROPIC_COMPATIBLE
}
