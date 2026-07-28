package com.interviewcoach.resume.infrastructure.ai;

import com.interviewcoach.common.security.agent.AgentPermission;
import com.interviewcoach.common.security.agent.AgentType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * 基于 Spring AI 的真实 LLM 服务实现，支持多厂商路由与主备切换。
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "resume.llm.enabled", havingValue = "true")
@RequiredArgsConstructor
public class SpringAiLlmService implements LlmService {

    private final ChatClientFactory chatClientFactory;
    private final ModelRouter modelRouter;

    @Override
    @AgentPermission({AgentType.INTERVIEWER, AgentType.EVALUATOR, AgentType.REPORT,
            AgentType.COACH, AgentType.RESUME_ANALYSIS, AgentType.JD_ANALYSIS})
    public String chat(String systemPrompt, String userPrompt) {
        return doChat(ModelTier.L2, systemPrompt, userPrompt);
    }

    private String doChat(ModelTier tier, String systemPrompt, String userPrompt) {
        VendorModel primary = modelRouter.selectPrimary(tier);
        try {
            return callModel(primary, systemPrompt, userPrompt);
        } catch (Exception e) {
            VendorModel fallback = modelRouter.selectFallback(tier);
            if (fallback.getVendorKey().equals(primary.getVendorKey())
                    && fallback.getModel().equals(primary.getModel())) {
                throw new RuntimeException("LLM 调用失败且无可用备用模型: " + primary.getVendorKey() + "/" + primary.getModel(), e);
            }
            modelRouter.recordFallback(tier, primary, fallback, e);
            return callModel(fallback, systemPrompt, userPrompt);
        }
    }

    private String callModel(VendorModel vendorModel, String systemPrompt, String userPrompt) {
        log.info("[SpringAiLlmService] 调用模型: vendor={}, model={}",
                vendorModel.getVendorKey(), vendorModel.getModel());
        ChatClient chatClient = chatClientFactory.createChatClient(vendorModel);
        return chatClient.prompt()
                .system(systemPrompt)
                .user(userPrompt)
                .call()
                .content();
    }
}
