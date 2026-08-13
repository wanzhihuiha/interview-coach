package com.interviewcoach.common.security.agent;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Agent 调用上下文持有者，基于线程本地栈支持 Agent 嵌套调用。
 *
 * <p>每个 Agent 入口应调用 {@link #enter(AgentType)}，退出时调用 {@link #leave()}，
 * 推荐使用 {@link AgentContext#runAs(AgentType, java.util.function.Supplier)} 避免遗漏。
 * ThreadLocal 不会自动跨线程传播，异步任务必须在其执行线程重新建立身份。</p>
 */
public final class AgentContextHolder {

    /**
     * 每个线程独立的 Agent 身份栈；栈顶是当前调用者，下面的元素用于嵌套调用结束后恢复身份。
     */
    private static final ThreadLocal<Deque<AgentType>> STACK = ThreadLocal.withInitial(ArrayDeque::new);

    private AgentContextHolder() {
    }

    /**
     * 将非空 Agent 身份压入当前线程栈，成为后续权限切面读取的当前调用者。
     */
    public static void enter(AgentType type) {
        if (type == null) {
            throw new IllegalArgumentException("AgentType 不能为空");
        }
        STACK.get().push(type);
    }

    /**
     * 弹出当前线程栈顶身份以恢复上一层嵌套上下文；空栈调用保持无操作。
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
     * 清空当前线程的全部 Agent 身份；不会影响其他线程的栈。
     */
    public static void reset() {
        STACK.get().clear();
    }
}
