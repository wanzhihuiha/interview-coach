package com.interviewcoach.common.security.agent;

/**
 * Agent 权限切面在调用身份、静态允许列表或动态评估不通过时抛出的拒绝异常。
 *
 * <p>异常会先穿过外层审计切面并被归类为 {@code DENIED}，随后继续向上抛出；
 * 权限切面不会在拒绝后执行目标 Tool 方法。</p>
 */
public class AgentAccessDeniedException extends RuntimeException {

    public AgentAccessDeniedException(String message) {
        super(message);
    }

    public AgentAccessDeniedException(String message, Throwable cause) {
        super(message, cause);
    }
}
