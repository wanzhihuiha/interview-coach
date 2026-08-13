package com.interviewcoach.common.security.agent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 从 {@code interview.agent-permissions} 绑定、由权限与审计切面共同消费的 Agent 安全配置。
 *
 * <p>配置示例：
 * <pre>
 * interview:
 *   agent-permissions:
 *     rules:
 *       "LlmService.chat":
 *         - INTERVIEWER
 *         - EVALUATOR
 * </pre></p>
 */
@Data
@ConfigurationProperties(prefix = "interview.agent-permissions")
public class AgentPermissionProperties {

    /**
     * 是否启用 Agent 权限校验：{@code true} 执行身份、静态规则和动态评估，
     * {@code false} 让权限切面直接执行目标方法；默认开启。
     */
    private boolean enabled = true;

    /**
     * 权限规则：key 为 {@code 声明类简单名.方法名}，value 为允许的 {@link AgentType} 英文名称列表。
     * 方法 key 缺失或列表为空时不覆盖注解默认值；配置未知枚举名会在权限解析时抛出异常并阻止目标调用。
     */
    private Map<String, List<String>> rules = new HashMap<>();

    /**
     * 是否输出允许调用和执行失败的应用审计日志；{@code false} 只关闭这些日志，
     * 不关闭审计实体持久化。拒绝日志还受 {@link #denyLog} 共同控制。
     */
    private boolean auditLog = true;

    /**
     * 是否单独输出权限拒绝日志；{@code true} 始终输出，{@code false} 时若 {@link #auditLog}
     * 仍开启也会输出拒绝日志。该字段不控制审计实体持久化。
     */
    private boolean denyLog = true;

    /**
     * 获取指定方法 key 的允许 Agent 名称；缺失时返回新的空列表，供权限切面回退到注解默认值。
     */
    public List<String> getAllowedAgents(String methodKey) {
        return rules.getOrDefault(methodKey, new ArrayList<>());
    }
}
