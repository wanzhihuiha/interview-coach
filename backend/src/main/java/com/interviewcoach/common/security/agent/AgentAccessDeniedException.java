package com.interviewcoach.common.security.agent;

/**
 * Agent 越权调用 Tool 时抛出的异常。
 */
public class AgentAccessDeniedException extends RuntimeException {

    public AgentAccessDeniedException(String message) {
        super(message);
    }

    public AgentAccessDeniedException(String message, Throwable cause) {
        super(message, cause);
    }
}
