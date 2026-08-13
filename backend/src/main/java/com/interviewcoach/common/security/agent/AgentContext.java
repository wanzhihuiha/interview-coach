package com.interviewcoach.common.security.agent;

import java.util.function.Supplier;

/**
 * Agent 入口调用的线程上下文执行助手，封装身份入栈、业务执行和 finally 出栈顺序。
 *
 * <p>结果供当前 Agent 调用链继续使用；上下文只绑定当前线程，不会自动传播到异步线程。</p>
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
        // 先将本次身份压入当前线程栈，使嵌套 Tool 权限切面能够读取调用者。
        AgentContextHolder.enter(type);
        try {
            // 在身份作用域内执行调用方逻辑，并把返回值原样交回上层 Agent。
            return action.get();
        } finally {
            // 无论正常返回还是抛错都弹出本次身份，恢复嵌套调用前的栈顶。
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
        // 先将本次身份压入当前线程栈，使嵌套 Tool 权限切面能够读取调用者。
        AgentContextHolder.enter(type);
        try {
            // 在身份作用域内执行无返回值业务逻辑。
            action.run();
        } finally {
            // 无论正常结束还是抛错都弹出本次身份，恢复嵌套调用前的栈顶。
            AgentContextHolder.leave();
        }
    }
}
