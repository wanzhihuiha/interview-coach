package com.interviewcoach.common.security.agent;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.core.annotation.Order;

/**
 * Agent 权限边界切面。
 *
 * <p>拦截所有带 {@link AgentPermission} 注解的方法，按以下顺序校验：
 * 1. 是否存在 Agent 调用身份；
 * 2. YAML 配置是否允许（存在则优先使用）；
 * 3. 注解默认允许列表是否允许；
 * 4. 注册的所有 {@link PermissionEvaluator} 是否全部通过。</p>
 *
 * <p>审计/拒绝日志由 {@link AgentAuditLogAspect} 统一处理，便于独立扩展。</p>
 */
@Slf4j
@Aspect
@Order(AgentPermissionAspect.PERMISSION_ORDER)
public class AgentPermissionAspect {

    /**
     * 权限切面优先级，必须低于审计切面，确保审计切面能完整包裹权限校验。
     */
    public static final int PERMISSION_ORDER = 200;

    private final AgentPermissionProperties properties;
    private final List<PermissionEvaluator> evaluators;

    public AgentPermissionAspect(AgentPermissionProperties properties, List<PermissionEvaluator> evaluators) {
        this.properties = properties;
        this.evaluators = evaluators != null ? evaluators : Collections.emptyList();
    }

    @Around("@annotation(agentPermission)")
    public Object around(ProceedingJoinPoint pjp, AgentPermission agentPermission) throws Throwable {
        if (!properties.isEnabled()) {
            return pjp.proceed();
        }

        MethodSignature signature = (MethodSignature) pjp.getSignature();
        Method method = signature.getMethod();
        String methodKey = buildMethodKey(method);
        AgentType caller = AgentContextHolder.current();

        Set<AgentType> allowed = resolveAllowedAgents(agentPermission, methodKey);

        if (caller == null) {
            deny(methodKey, "未设置 Agent 调用身份");
        }

        if (!allowed.contains(caller)) {
            deny(methodKey, "Agent [" + caller + "] 不在允许列表 " + allowed);
        }

        for (PermissionEvaluator evaluator : evaluators) {
            if (!evaluator.evaluate(caller, pjp.getTarget(), method, pjp.getArgs())) {
                deny(methodKey, "动态权限评估器拒绝：" + evaluator.getClass().getSimpleName());
            }
        }

        return pjp.proceed();
    }

    /**
     * 解析方法允许的 Agent 列表：YAML 配置优先，否则使用注解默认值。
     */
    private Set<AgentType> resolveAllowedAgents(AgentPermission annotation, String methodKey) {
        List<String> yamlAllowed = properties.getAllowedAgents(methodKey);
        if (!yamlAllowed.isEmpty()) {
            return yamlAllowed.stream()
                    .map(String::trim)
                    .map(AgentType::valueOf)
                    .collect(Collectors.toCollection(HashSet::new));
        }
        return Arrays.stream(annotation.value()).collect(Collectors.toCollection(HashSet::new));
    }

    private String buildMethodKey(Method method) {
        return method.getDeclaringClass().getSimpleName() + "." + method.getName();
    }

    private void deny(String methodKey, String reason) {
        throw new AgentAccessDeniedException("无权调用 [" + methodKey + "]: " + reason);
    }
}
