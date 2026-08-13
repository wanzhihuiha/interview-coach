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
     * 权限切面优先级，必须大于审计切面的 100，确保审计切面能完整包裹权限校验。
     * 200 是当前固定排序值，精确取值依据缺失。
     */
    public static final int PERMISSION_ORDER = 200;

    /** 提供总开关和按方法覆盖注解默认允许身份的 YAML 规则。 */
    private final AgentPermissionProperties properties;
    /** 静态身份列表通过后依次执行的动态业务权限评估器；构造时空值转换为空列表。 */
    private final List<PermissionEvaluator> evaluators;

    public AgentPermissionAspect(AgentPermissionProperties properties, List<PermissionEvaluator> evaluators) {
        this.properties = properties;
        this.evaluators = evaluators != null ? evaluators : Collections.emptyList();
    }

    /**
     * 在目标方法执行前完成总开关、调用身份、YAML/注解允许列表和全部动态评估器校验。
     *
     * <p>任一步拒绝都会抛出 {@link AgentAccessDeniedException} 且不执行目标方法；动态评估器异常
     * 保持原样向外传播，由外层审计切面归类为失败而不是权限拒绝。</p>
     */
    @Around("@annotation(agentPermission)")
    public Object around(ProceedingJoinPoint pjp, AgentPermission agentPermission) throws Throwable {
        if (!properties.isEnabled()) {
            // 总开关关闭时跳过身份和评估器校验，直接执行目标方法；外层审计切面仍可记录调用。
            return pjp.proceed();
        }

        MethodSignature signature = (MethodSignature) pjp.getSignature();
        Method method = signature.getMethod();
        String methodKey = buildMethodKey(method);
        // 身份只能来自当前服务端线程上下文，不从目标方法参数推断。
        AgentType caller = AgentContextHolder.current();

        // YAML 非空规则优先于注解默认值；两者都为空时任何身份都不在允许集合中。
        Set<AgentType> allowed = resolveAllowedAgents(agentPermission, methodKey);

        if (caller == null) {
            deny(methodKey, "未设置 Agent 调用身份");
        }

        if (!allowed.contains(caller)) {
            deny(methodKey, "Agent [" + caller + "] 不在允许列表 " + allowed);
        }

        for (PermissionEvaluator evaluator : evaluators) {
            // 静态允许通过后，所有动态评估器仍必须逐个同意；任一 false 立即拒绝。
            if (!evaluator.evaluate(caller, pjp.getTarget(), method, pjp.getArgs())) {
                deny(methodKey, "动态权限评估器拒绝：" + evaluator.getClass().getSimpleName());
            }
        }

        // 只有静态和动态权限全部通过后才执行目标 Tool 方法。
        return pjp.proceed();
    }

    /**
     * 解析方法允许的 Agent 列表：YAML 配置优先，否则使用注解默认值。
     */
    private Set<AgentType> resolveAllowedAgents(AgentPermission annotation, String methodKey) {
        // 缺失或空 YAML 列表表示没有覆盖，继续使用方法注解中的默认身份。
        List<String> yamlAllowed = properties.getAllowedAgents(methodKey);
        if (!yamlAllowed.isEmpty()) {
            return yamlAllowed.stream()
                    .map(String::trim)
                    .map(AgentType::valueOf)
                    .collect(Collectors.toCollection(HashSet::new));
        }
        return Arrays.stream(annotation.value()).collect(Collectors.toCollection(HashSet::new));
    }

    /** 返回与 YAML 规则键一致的 {@code 声明类简单名.方法名}。 */
    private String buildMethodKey(Method method) {
        return method.getDeclaringClass().getSimpleName() + "." + method.getName();
    }

    /** 抛出稳定拒绝异常；调用该方法后权限切面不会继续执行目标方法。 */
    private void deny(String methodKey, String reason) {
        throw new AgentAccessDeniedException("无权调用 [" + methodKey + "]: " + reason);
    }
}
