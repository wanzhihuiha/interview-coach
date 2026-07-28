package com.interviewcoach.resume.infrastructure.ai.config;

import java.util.HashMap;
import java.util.Map;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * LLM 配置属性，支持多厂商（api-key / base-url / model）配置。
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "resume.llm")
public class LlmProperties {

    /**
     * 是否启用真实 LLM（true=Spring AI 实现，false=Mock）。
     */
    private boolean enabled = false;

    /**
     * LLM 调用超时时间（秒）。
     */
    private int timeoutSeconds = 60;

    /**
     * 失败重试次数。
     */
    private int retryTimes = 1;

    /**
     * 采样温度，控制输出随机性，范围 0.0-1.0。
     */
    private double temperature = 0.3;

    /**
     * 最大输出 token 数。
     */
    private int maxTokens = 4096;

    /**
     * Top-P 采样，控制输出多样性，范围 0.0-1.0。
     */
    private double topP = 0.9;

    /**
     * 厂商配置，key 为厂商标识（如 dashscope / openai / zhipu）。
     */
    private Map<String, VendorConfig> vendors = new HashMap<>();

    /**
     * 各层级模型配置，primary / fallback 引用 vendor key。
     */
    private TierConfig tiers = new TierConfig();

    @Getter
    @Setter
    public static class VendorConfig {
        /**
         * 厂商类型：DASHSCOPE / OPENAI_COMPATIBLE / ANTHROPIC_COMPATIBLE。
         */
        private String type;

        /**
         * 是否启用该厂商。
         */
        private boolean enabled = false;

        /**
         * API Key。
         */
        private String apiKey;

        /**
         * Base URL，OpenAI 兼容接口必填；DashScope 可留空使用默认地址。
         */
        private String baseUrl;

        /**
         * 默认模型名称。
         */
        private String model;
    }

    @Getter
    @Setter
    public static class TierConfig {
        private VendorModelConfig l1 = new VendorModelConfig("dashscope", "dashscope");
        private VendorModelConfig l2 = new VendorModelConfig("dashscope", "dashscope");
        private VendorModelConfig l3 = new VendorModelConfig("dashscope", "dashscope");
    }

    @Getter
    @Setter
    public static class VendorModelConfig {
        /**
         * 主用厂商标识，可带模型名如 "dashscope:qwen-plus"。
         */
        private String primary;

        /**
         * 备用厂商标识，可带模型名如 "openai:gpt-3.5-turbo"。
         */
        private String fallback;

        public VendorModelConfig() {
        }

        public VendorModelConfig(String primary, String fallback) {
            this.primary = primary;
            this.fallback = fallback;
        }
    }
}
