package com.interviewcoach.common.security.agent;

import java.util.List;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;

/**
 * 由 Spring 启动装配加载的 Agent 权限与审计切面配置。
 *
 * <p>它启用 AspectJ 自动代理和配置属性绑定，并分别创建外层审计切面与内层权限切面；
 * 业务 Agent 和 Tool 通过方法注解进入这两条链路。</p>
 */
@Configuration
@EnableAspectJAutoProxy
@EnableConfigurationProperties(AgentPermissionProperties.class)
public class AgentSecurityConfig {

    /**
     * 使用安全配置和异步存储服务创建审计切面，使允许、拒绝和失败调用可记录并持久化。
     */
    @Bean
    public AgentAuditLogAspect agentAuditLogAspect(
            AgentPermissionProperties properties,
            AgentAuditLogStorageService storageService) {
        return new AgentAuditLogAspect(properties, storageService);
    }

    /**
     * 使用静态配置及 Spring 收集的动态评估器创建权限切面，在目标 Tool 执行前实施校验。
     */
    @Bean
    public AgentPermissionAspect agentPermissionAspect(
            AgentPermissionProperties properties,
            List<PermissionEvaluator> evaluators) {
        return new AgentPermissionAspect(properties, evaluators);
    }
}
