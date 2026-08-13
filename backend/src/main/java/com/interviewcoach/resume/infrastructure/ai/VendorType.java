package com.interviewcoach.resume.infrastructure.ai;

/**
 * 客户端工厂支持的模型协议类型，决定认证、地址和 Spring AI 客户端构建分支。
 */
public enum VendorType {
    /**
     * DashScope 协议分支，使用 Spring AI Alibaba 客户端。
     */
    DASHSCOPE,
    /**
     * OpenAI 兼容协议分支，使用配置的 Base URL、API Key 和模型名。
     */
    OPENAI_COMPATIBLE,
    /**
     * Anthropic Messages API 兼容协议分支，使用配置的 Base URL、API Key 和模型名。
     */
    ANTHROPIC_COMPATIBLE
}
