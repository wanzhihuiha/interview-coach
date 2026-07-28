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
 * 根据厂商配置动态创建 ChatClient。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatClientFactory {

    private final LlmProperties llmProperties;

    /**
     * 为指定厂商和模型创建 ChatClient。
     */
    public ChatClient createChatClient(VendorModel vendorModel) {
        return switch (vendorModel.getVendorType()) {
            case DASHSCOPE -> createDashScopeChatClient(vendorModel);
            case OPENAI_COMPATIBLE -> createOpenAiCompatibleChatClient(vendorModel);
            case ANTHROPIC_COMPATIBLE -> createAnthropicCompatibleChatClient(vendorModel);
        };
    }

    private ChatClient createDashScopeChatClient(VendorModel vendorModel) {
        String apiKey = vendorModel.getVendorConfig().getApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("DashScope API Key 不能为空: " + vendorModel.getVendorKey());
        }
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

    private ChatClient createOpenAiCompatibleChatClient(VendorModel vendorModel) {
        LlmProperties.VendorConfig config = vendorModel.getVendorConfig();
        if (config.getApiKey() == null || config.getApiKey().isBlank()) {
            throw new IllegalArgumentException("API Key 不能为空: " + vendorModel.getVendorKey());
        }
        if (config.getBaseUrl() == null || config.getBaseUrl().isBlank()) {
            throw new IllegalArgumentException("Base URL 不能为空: " + vendorModel.getVendorKey());
        }
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

    private ChatClient createAnthropicCompatibleChatClient(VendorModel vendorModel) {
        LlmProperties.VendorConfig config = vendorModel.getVendorConfig();
        if (config.getApiKey() == null || config.getApiKey().isBlank()) {
            throw new IllegalArgumentException("API Key 不能为空: " + vendorModel.getVendorKey());
        }
        if (config.getBaseUrl() == null || config.getBaseUrl().isBlank()) {
            throw new IllegalArgumentException("Base URL 不能为空: " + vendorModel.getVendorKey());
        }
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
