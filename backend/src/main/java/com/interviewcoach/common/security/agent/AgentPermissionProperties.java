package com.interviewcoach.common.security.agent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Agent 权限矩阵配置。
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
     * 是否启用 Agent 权限校验，默认开启。
     */
    private boolean enabled = true;

    /**
     * 权限规则：key 为 {@code 声明类简单名.方法名}，value 为允许的 Agent 名称列表。
     */
    private Map<String, List<String>> rules = new HashMap<>();

    /**
     * 是否记录每次权限校验的审计日志。
     */
    private boolean auditLog = true;

    /**
     * 是否记录被拒绝的访问日志。
     */
    private boolean denyLog = true;

    /**
     * 获取指定方法 key 的允许 Agent 列表，不存在时返回空列表。
     */
    public List<String> getAllowedAgents(String methodKey) {
        return rules.getOrDefault(methodKey, new ArrayList<>());
    }
}
