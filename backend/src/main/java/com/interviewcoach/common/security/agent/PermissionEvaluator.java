package com.interviewcoach.common.security.agent;

import java.lang.reflect.Method;

/**
 * 动态权限评估器扩展点。
 *
 * <p>实现类注册为 Spring Bean 后，会在静态注解/YAML 校验通过后执行，
 * 用于根据运行时参数、业务状态做二次判断。</p>
 */
public interface PermissionEvaluator {

    /**
     * 评估当前 Agent 是否有权调用目标方法。
     *
     * @param caller 当前调用 Agent，可能为 null
     * @param target 目标 Tool 实例
     * @param method 被调用的方法
     * @param args   方法参数
     * @return true 表示允许，false 表示拒绝
     */
    boolean evaluate(AgentType caller, Object target, Method method, Object[] args);
}
