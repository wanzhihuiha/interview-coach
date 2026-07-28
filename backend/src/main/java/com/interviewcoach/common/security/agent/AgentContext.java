package com.interviewcoach.common.security.agent;

import java.util.function.Supplier;

/**
 * Agent 上下文执行助手，封装 enter/leave 的 try-finally 逻辑。
 */
public final class AgentContext {

    private AgentContext() {
    }

    /**
     * 以指定 Agent 身份执行逻辑，执行完成后自动退出上下文。
     *
     * @param type   Agent 身份
     * @param action 需要执行的逻辑
     * @param <T>    返回值类型
     * @return 执行结果
     */
    public static <T> T runAs(AgentType type, Supplier<T> action) {
        AgentContextHolder.enter(type);
        try {
            return action.get();
        } finally {
            AgentContextHolder.leave();
        }
    }

    /**
     * 以指定 Agent 身份执行无返回值逻辑。
     *
     * @param type   Agent 身份
     * @param action 需要执行的逻辑
     */
    public static void runAs(AgentType type, Runnable action) {
        AgentContextHolder.enter(type);
        try {
            action.run();
        } finally {
            AgentContextHolder.leave();
        }
    }
}
