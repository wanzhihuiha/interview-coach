package com.interviewcoach.common.security.agent;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记 Tool 方法需要进行 Agent 权限校验。
 *
 * <p>value 为空时，表示该方法的权限完全由 YAML 配置或动态评估器决定；
 * value 非空时，作为默认允许的 Agent 列表，YAML 中针对同一方法 key 的配置可覆盖它。</p>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface AgentPermission {

    /**
     * 默认允许调用的 Agent 列表。
     */
    AgentType[] value() default {};
}
