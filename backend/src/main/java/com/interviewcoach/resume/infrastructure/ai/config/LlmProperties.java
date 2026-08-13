package com.interviewcoach.resume.infrastructure.ai.config;

import java.util.HashMap;
import java.util.Map;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 从 {@code resume.llm} 读取的模型开关、采样参数、厂商凭据引用和分层路由配置。
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
     * 预留的 LLM 调用超时时间，单位为秒；当前主源码没有读取该字段，因此调整不会改变实际超时。
     */
    private int timeoutSeconds = 60;

    /**
     * 预留的失败重试次数；当前主源码没有读取该字段，实际只执行主模型及至多一次备用模型调用。
     */
    private int retryTimes = 1;

    /**
     * 创建模型客户端时传入的采样温度；当前默认 0.3，精确默认值依据缺失，调高通常增加输出随机性。
     */
    private double temperature = 0.3;

    /**
     * 创建模型客户端时传入的最大输出 token 数；当前默认 4096，精确上限依据缺失，调高会放宽单次输出预算。
     */
    private int maxTokens = 4096;

    /**
     * 创建模型客户端时传入的 Top-P 采样参数；当前默认 0.9，精确默认值依据缺失。
     */
    private double topP = 0.9;

    /**
     * 厂商键到协议、启用状态、认证、地址和默认模型的映射；路由表达式通过键引用，不代表生产一定启用某厂商。
     */
    private Map<String, VendorConfig> vendors = new HashMap<>();

    /** 模型层级到主用、备用路由表达式的配置。 */
    private TierConfig tiers = new TierConfig();

    /** 单个厂商连接配置，由路由器校验后交给客户端工厂使用。 */
    @Getter
    @Setter
    public static class VendorConfig {
        /**
         * 厂商类型：DASHSCOPE / OPENAI_COMPATIBLE / ANTHROPIC_COMPATIBLE。
         */
        private String type;

        /** 是否允许路由到该厂商；false 时路由解析直接失败。 */
        private boolean enabled = false;

        /** 远程模型认证密钥，属于敏感配置，不得写入日志或结果。 */
        private String apiKey;

        /**
         * Base URL，OpenAI 兼容接口必填；DashScope 可留空使用默认地址。
         */
        private String baseUrl;

        /** 路由表达式未显式指定模型时使用的默认模型名称。 */
        private String model;
    }

    /** 三个任务层级的主备模型路由槽位。 */
    @Getter
    @Setter
    public static class TierConfig {
        /** 轻量任务路由；当前主源码尚无 L1 调用方。 */
        private VendorModelConfig l1 = new VendorModelConfig("dashscope", "dashscope");
        /** 标准任务路由；当前 LLM 服务的通用对话入口使用该槽位。 */
        private VendorModelConfig l2 = new VendorModelConfig("dashscope", "dashscope");
        /** 高复杂度任务路由；当前主源码尚无 L3 调用方。 */
        private VendorModelConfig l3 = new VendorModelConfig("dashscope", "dashscope");
    }

    /** 一个层级的主用和备用路由表达式，可使用 {@code vendorKey:model} 覆盖默认模型。 */
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

        /** 供 Spring 配置绑定创建空路由项。 */
        public VendorModelConfig() {
        }

        /** 创建带主用和备用表达式的默认路由项。 */
        public VendorModelConfig(String primary, String fallback) {
            this.primary = primary;
            this.fallback = fallback;
        }
    }
}
