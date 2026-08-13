package com.interviewcoach.resume.infrastructure.ai;

import com.interviewcoach.common.security.agent.AgentPermission;
import com.interviewcoach.common.security.agent.AgentType;
import java.time.Duration;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * 在 {@code resume.llm.enabled=true} 时装配的真实模型传输服务，按配置路由主模型并在失败时尝试一个不同的备用模型。
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "resume.llm.enabled", havingValue = "true")
@RequiredArgsConstructor
public class SpringAiLlmService implements LlmService {

    /** DEBUG 原文单字段最多保留 20000 个字符；依据缺失，调大会增加敏感数据暴露，调小会截断更多排障上下文。 */
    private static final int MAX_DEBUG_CONTENT_LENGTH = 20000;

    /** 根据路由结果创建对应协议的真实模型客户端。 */
    private final ChatClientFactory chatClientFactory;
    /** 从运行时配置解析 L2 主用和备用厂商、模型。 */
    private final ModelRouter modelRouter;

    /**
     * 使用 L2 模型层级处理通用 Agent 对话；权限与调用耗时同时由 Agent 审计切面记录。
     */
    @Override
    @AgentPermission({AgentType.INTERVIEWER, AgentType.EVALUATOR, AgentType.REPORT,
            AgentType.COACH, AgentType.RESUME_ANALYSIS, AgentType.JD_ANALYSIS})
    public String chat(String systemPrompt, String userPrompt) {
        // 当前通用入口固定使用 L2 路由；生产实际厂商和主备关系由配置决定。
        return doChat(ModelTier.L2, systemPrompt, userPrompt);
    }

    /**
     * 先调用主模型；主模型失败时仅在备用配置不同的情况下执行一次降级调用。
     */
    private String doChat(ModelTier tier, String systemPrompt, String userPrompt) {
        // 先解析主路由并执行真实远程调用，配置或调用失败均进入备用判断。
        VendorModel primary = modelRouter.selectPrimary(tier);
        try {
            return callModel(primary, systemPrompt, userPrompt);
        } catch (Exception e) {
            // 仅当备用厂商或模型与主用不同才执行第二次远程调用，否则保留原异常作为失败原因。
            VendorModel fallback = modelRouter.selectFallback(tier);
            if (fallback.getVendorKey().equals(primary.getVendorKey())
                    && fallback.getModel().equals(primary.getModel())) {
                throw new RuntimeException("LLM 调用失败且无可用备用模型: " + primary.getVendorKey() + "/" + primary.getModel(), e);
            }
            modelRouter.recordFallback(tier, primary, fallback, e);
            return callModel(fallback, systemPrompt, userPrompt);
        }
    }

    /**
     * 执行单次模型调用。INFO 只记录路由、长度和耗时，不包含提示词、响应或异常消息；
     * DEBUG 额外记录开发排障原文，并统一转成单行、按长度截断。
     */
    private String callModel(VendorModel vendorModel, String systemPrompt, String userPrompt) {
        Instant startedAt = Instant.now();
        log.info("[LlmCall] 模型调用开始: vendor={}, model={}",
                vendorModel.getVendorKey(), vendorModel.getModel());
        if (log.isDebugEnabled()) {
            log.debug("[LlmCall] 模型请求详情: vendor={}, model={}, systemPrompt={}, userPrompt={}",
                    vendorModel.getVendorKey(),
                    vendorModel.getModel(),
                    debugContent(systemPrompt),
                    debugContent(userPrompt));
        }
        try {
            // 用路由携带的认证、地址和模型配置创建客户端；构建失败同样交由上层降级。
            ChatClient chatClient = chatClientFactory.createChatClient(vendorModel);
            // 将系统提示词和用户提示词发送给所选外部模型供应商，空响应原样返回给 Agent 判定失败。
            String response = chatClient.prompt()
                    .system(systemPrompt)
                    .user(userPrompt)
                    .call()
                    .content();
            long durationMs = Duration.between(startedAt, Instant.now()).toMillis();
            if (response == null || response.isBlank()) {
                log.warn("[LlmCall] 模型调用完成但响应为空: vendor={}, model={}, durationMs={}",
                        vendorModel.getVendorKey(), vendorModel.getModel(), durationMs);
            } else {
                log.info("[LlmCall] 模型调用完成: vendor={}, model={}, responseLength={}, durationMs={}",
                        vendorModel.getVendorKey(), vendorModel.getModel(), response.length(), durationMs);
                if (log.isDebugEnabled()) {
                    log.debug("[LlmCall] 模型响应详情: vendor={}, model={}, response={}",
                            vendorModel.getVendorKey(), vendorModel.getModel(), debugContent(response));
                }
            }
            return response;
        } catch (RuntimeException e) {
            log.warn("[LlmCall] 模型调用失败: vendor={}, model={}, errorType={}, durationMs={}",
                    vendorModel.getVendorKey(),
                    vendorModel.getModel(),
                    e.getClass().getSimpleName(),
                    Duration.between(startedAt, Instant.now()).toMillis());
            if (log.isDebugEnabled()) {
                log.debug("[LlmCall] 模型调用异常详情: vendor={}, model={}, errorMessage={}",
                        vendorModel.getVendorKey(), vendorModel.getModel(), errorMessage(e));
            }
            throw e;
        }
    }

    /**
     * 保留开发排障所需原文，同时限制单条 DEBUG 日志大小；该方法不用于 INFO 日志。
     */
    private String debugContent(String value) {
        if (value == null) {
            return "<null>";
        }
        String singleLine = value.replace("\r", "\\r").replace("\n", "\\n");
        return singleLine.length() <= MAX_DEBUG_CONTENT_LENGTH
                ? singleLine
                : singleLine.substring(0, MAX_DEBUG_CONTENT_LENGTH)
                        + "...(truncated,totalLength=" + singleLine.length() + ")";
    }

    /**
     * 提取最深层异常的单行消息用于 DEBUG，最多追踪 20 层并截断至 500 字符；两个固定值依据缺失，
     * 调大增加日志体积和内部信息暴露，调小会减少可定位上下文。
     */
    private String errorMessage(Throwable error) {
        Throwable current = error;
        for (int depth = 0; current.getCause() != null && depth < 20; depth++) {
            current = current.getCause();
        }
        String message = current.getMessage();
        if (message == null || message.isBlank()) {
            return "-";
        }
        String singleLine = message.replace("\r", "\\r").replace("\n", "\\n");
        return singleLine.length() <= 500
                ? singleLine
                : singleLine.substring(0, 500) + "...(truncated)";
    }
}
