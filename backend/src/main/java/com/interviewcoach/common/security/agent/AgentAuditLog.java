package com.interviewcoach.common.security.agent;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Agent 调用审计注解。
 *
 * <p>当前 {@link AgentAuditLogAspect} 的切点只匹配方法注解；虽然本注解的 Java 声明允许标在类型上，
 * 仅标类型目前不会触发审计。方法注解会记录调用身份、目标方法、耗时和结果状态，供日志与
 * 审计表查询；让类型注解生效需要修改切点，属于本次注释整改范围外。</p>
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface AgentAuditLog {

    /**
     * 审计操作名称；为空时使用 {@code 声明类简单名.方法名} 作为操作名。
     */
    String value() default "";

    /**
     * 是否记录方法参数类型摘要：{@code true} 记录各参数运行时类型名，{@code false} 不记录；
     * 两种情况都不会由该切面序列化参数正文。
     */
    boolean logArgs() default false;

    /**
     * 是否记录返回值类型摘要：{@code true} 记录返回对象运行时类型名，{@code false} 不记录；
     * 两种情况都不会由该切面序列化返回值正文。
     */
    boolean logResult() default false;
}
