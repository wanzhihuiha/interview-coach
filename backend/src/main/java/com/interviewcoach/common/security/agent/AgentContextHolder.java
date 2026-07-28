package com.interviewcoach.common.security.agent;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Agent 调用上下文持有者，基于线程本地栈支持 Agent 嵌套调用。
 *
 * <p>每个 Agent 入口应调用 {@link #enter(AgentType)}，退出时调用 {@link #leave()}，
 * 推荐使用 {@link AgentContext#runAs(AgentType, java.util.function.Supplier)} 避免遗漏。</p>
 */
public final class AgentContextHolder {

    private static final ThreadLocal<Deque<AgentType>> STACK = ThreadLocal.withInitial(ArrayDeque::new);

    private AgentContextHolder() {
    }

    /**
     * 进入 Agent 上下文。
     */
    public static void enter(AgentType type) {
        if (type == null) {
            throw new IllegalArgumentException("AgentType 不能为空");
        }
        STACK.get().push(type);
    }

    /**
     * 退出当前 Agent 上下文。
     */
    public static void leave() {
        Deque<AgentType> deque = STACK.get();
        if (!deque.isEmpty()) {
            deque.pop();
        }
    }

    /**
     * 获取当前调用 Agent。
     *
     * @return 当前栈顶 Agent，不存在时返回 null
     */
    public static AgentType current() {
        return STACK.get().peek();
    }

    /**
     * 清空当前线程的 Agent 上下文。
     */
    public static void reset() {
        STACK.get().clear();
    }
}
