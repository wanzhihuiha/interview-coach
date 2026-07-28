package com.interviewcoach.common.security.agent;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Agent 调用审计注解。
 *
 * <p>标记在 Tool 或 Agent 方法上，由 {@link AgentAuditLogAspect} 拦截并记录调用信息，
 * 包括调用者身份、目标方法、执行耗时、成功/拒绝状态等，便于后续安全审计与行为分析。</p>
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface AgentAuditLog {

    /**
     * 审计操作名称，为空时自动生成。
     */
    String value() default "";

    /**
     * 是否记录方法参数。
     */
    boolean logArgs() default false;

    /**
     * 是否记录返回值。
     */
    boolean logResult() default false;
}
