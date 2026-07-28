package com.interviewcoach.common.security.agent;

import java.util.List;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;

/**
 * Agent 权限安全配置类。
 */
@Configuration
@EnableAspectJAutoProxy
@EnableConfigurationProperties(AgentPermissionProperties.class)
public class AgentSecurityConfig {

    /**
     * 注册 Agent 审计切面。
     */
    @Bean
    public AgentAuditLogAspect agentAuditLogAspect(
            AgentPermissionProperties properties,
            AgentAuditLogStorageService storageService) {
        return new AgentAuditLogAspect(properties, storageService);
    }

    /**
     * 注册 Agent 权限切面。
     */
    @Bean
    public AgentPermissionAspect agentPermissionAspect(
            AgentPermissionProperties properties,
            List<PermissionEvaluator> evaluators) {
        return new AgentPermissionAspect(properties, evaluators);
    }
}
