package com.interviewcoach.interview.infrastructure.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 面试模块 LLM API 限流配置。
 */
@Data
@Component
@ConfigurationProperties(prefix = "interview.llm-rate-limit")
public class InterviewLlmRateLimitProperties {

    /**
     * 是否启用 LLM 限流。
     */
    private boolean enabled = true;

    /**
     * 每分钟最大请求数。
     */
    private int requestsPerMinute = 60;

    /**
     * 每分钟最大 Token 数（估算值）。
     */
    private int tokensPerMinute = 100000;
}
