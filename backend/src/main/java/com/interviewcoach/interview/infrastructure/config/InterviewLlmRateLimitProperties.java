package com.interviewcoach.interview.infrastructure.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 绑定 {@code interview.llm-rate-limit} 的面试 LLM Redis 限流配置。
 * 限流器读取这些值控制所有经 {@code LlmInterviewService} 的旧/安全调用入口。
 */
@Data
@Component
@ConfigurationProperties(prefix = "interview.llm-rate-limit")
public class InterviewLlmRateLimitProperties {

    /**
     * 是否启用 Redis 请求数和估算 Token 数扣减；关闭时所有调用直接放行。
     */
    private boolean enabled = true;

    /**
     * 全部面试 LLM 调用共享的每 60 秒请求容量，默认 60；精确 provider 容量依据缺失。
     * 调低会更早拒绝调用，调高可能更接近或超过外部配额。
     */
    private int requestsPerMinute = 60;

    /**
     * 全部面试 LLM 调用共享的每 60 秒估算 Token 容量，默认 100000；精确容量依据缺失。
     * 本值使用字符长度粗估而非 provider 实际计费 Token。
     */
    private int tokensPerMinute = 100000;
}
