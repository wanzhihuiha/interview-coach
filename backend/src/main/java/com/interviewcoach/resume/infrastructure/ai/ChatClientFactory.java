package com.interviewcoach.resume.infrastructure.ai;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import com.interviewcoach.resume.infrastructure.ai.config.LlmProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.anthropic.api.AnthropicApi;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.stereotype.Component;

/**
 * 根据路由结果和简历 LLM 配置创建一次真实模型客户端，供 Spring AI 传输服务发送提示词。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatClientFactory {

    /** 提供认证、地址、模型和采样参数的配置对象；生产实际厂商由运行环境决定。 */
    private final LlmProperties llmProperties;

    /**
     * 为指定厂商和模型创建 ChatClient。
     */
    public ChatClient createChatClient(VendorModel vendorModel) {
        // 按已解析的协议类型构建对应客户端；配置缺失时由具体分支抛错并阻止远程调用。
        return switch (vendorModel.getVendorType()) {
            case DASHSCOPE -> createDashScopeChatClient(vendorModel);
            case OPENAI_COMPATIBLE -> createOpenAiCompatibleChatClient(vendorModel);
            case ANTHROPIC_COMPATIBLE -> createAnthropicCompatibleChatClient(vendorModel);
        };
    }

    /** 使用配置中的 API Key、模型名和采样参数创建 DashScope 客户端。 */
    private ChatClient createDashScopeChatClient(VendorModel vendorModel) {
        String apiKey = vendorModel.getVendorConfig().getApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("DashScope API Key 不能为空: " + vendorModel.getVendorKey());
        }
        // 用路由结果中的凭据创建协议 API，并把全局采样参数绑定为本次客户端的默认选项。
        DashScopeApi dashScopeApi = new DashScopeApi(apiKey);
        DashScopeChatOptions options = DashScopeChatOptions.builder()
                .withModel(vendorModel.getModel())
                .withTemperature(llmProperties.getTemperature())
                .withMaxToken(llmProperties.getMaxTokens())
                .withTopP(llmProperties.getTopP())
                .build();
        DashScopeChatModel chatModel = new DashScopeChatModel(dashScopeApi, options);
        return ChatClient.builder(chatModel).build();
    }

    /** 使用配置中的 Base URL、API Key、模型名和采样参数创建 OpenAI 兼容客户端。 */
    private ChatClient createOpenAiCompatibleChatClient(VendorModel vendorModel) {
        LlmProperties.VendorConfig config = vendorModel.getVendorConfig();
        if (config.getApiKey() == null || config.getApiKey().isBlank()) {
            throw new IllegalArgumentException("API Key 不能为空: " + vendorModel.getVendorKey());
        }
        if (config.getBaseUrl() == null || config.getBaseUrl().isBlank()) {
            throw new IllegalArgumentException("Base URL 不能为空: " + vendorModel.getVendorKey());
        }
        // 将配置地址和凭据交给 OpenAI 兼容 API，再用路由模型名与采样参数构建客户端。
        OpenAiApi openAiApi = new OpenAiApi(config.getBaseUrl(), config.getApiKey());
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .model(vendorModel.getModel())
                .temperature(llmProperties.getTemperature())
                .maxTokens(llmProperties.getMaxTokens())
                .topP(llmProperties.getTopP())
                .build();
        OpenAiChatModel chatModel = new OpenAiChatModel(openAiApi, options);
        return ChatClient.builder(chatModel).build();
    }

    /** 使用配置中的 Base URL、API Key、模型名和采样参数创建 Anthropic 兼容客户端。 */
    private ChatClient createAnthropicCompatibleChatClient(VendorModel vendorModel) {
        LlmProperties.VendorConfig config = vendorModel.getVendorConfig();
        if (config.getApiKey() == null || config.getApiKey().isBlank()) {
            throw new IllegalArgumentException("API Key 不能为空: " + vendorModel.getVendorKey());
        }
        if (config.getBaseUrl() == null || config.getBaseUrl().isBlank()) {
            throw new IllegalArgumentException("Base URL 不能为空: " + vendorModel.getVendorKey());
        }
        // 将配置地址和凭据交给 Anthropic 兼容 API，再用路由模型名与采样参数构建客户端。
        AnthropicApi anthropicApi = new AnthropicApi(config.getBaseUrl(), config.getApiKey());
        AnthropicChatOptions options = AnthropicChatOptions.builder()
                .model(vendorModel.getModel())
                .temperature(llmProperties.getTemperature())
                .maxTokens(llmProperties.getMaxTokens())
                .topP(llmProperties.getTopP())
                .build();
        AnthropicChatModel chatModel = AnthropicChatModel.builder()
                .anthropicApi(anthropicApi)
                .defaultOptions(options)
                .build();
        return ChatClient.builder(chatModel).build();
    }
}
