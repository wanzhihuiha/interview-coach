package com.interviewcoach.common.observability;

import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.MDC;

/**
 * 统一维护请求和异步任务的 MDC 字段，并在作用域结束后恢复线程原有上下文。
 *
 * <p>MDC 绑定在线程上，不会自动随任务进入线程池。调用方应使用 try-with-resources
 * 关闭 {@link Scope}，避免复用线程携带上一请求或上一任务的诊断字段。</p>
 */
public final class DiagnosticContext {

    public static final String REQUEST_ID = "requestId";
    public static final String TASK_ID = "taskId";
    public static final String POSITION_ID = "positionId";

    private DiagnosticContext() {
    }

    /**
     * 为 HTTP 请求打开诊断作用域；关闭作用域时恢复进入前的 requestId、taskId 和 positionId。
     */
    public static Scope openRequest(String requestId) {
        return open(requestId, null, null);
    }

    /**
     * 为异步岗位任务显式重建诊断作用域。
     *
     * <p>传入 {@code null} 的字段会在当前作用域内清除，以免线程池复用导致旧 MDC 串入新任务；
     * 作用域关闭后仍会恢复线程进入前的值。</p>
     */
    public static Scope openPositionTask(String requestId, Long taskId, Long positionId) {
        return open(
                requestId,
                taskId == null ? null : taskId.toString(),
                positionId == null ? null : positionId.toString());
    }

    /**
     * 返回当前线程的请求标识，供提交异步任务前显式捕获并传递。
     */
    public static String currentRequestId() {
        return MDC.get(REQUEST_ID);
    }

    /**
     * 先保存所有旧值再整体替换，使嵌套作用域也能按进入顺序正确恢复。
     */
    private static Scope open(String requestId, String taskId, String positionId) {
        Map<String, String> previousValues = new LinkedHashMap<>();
        replace(previousValues, REQUEST_ID, requestId);
        replace(previousValues, TASK_ID, taskId);
        replace(previousValues, POSITION_ID, positionId);
        return () -> previousValues.forEach(DiagnosticContext::restore);
    }

    private static void replace(Map<String, String> previousValues, String key, String value) {
        previousValues.put(key, MDC.get(key));
        if (value == null) {
            MDC.remove(key);
        } else {
            MDC.put(key, value);
        }
    }

    private static void restore(String key, String value) {
        if (value == null) {
            MDC.remove(key);
        } else {
            MDC.put(key, value);
        }
    }

    @FunctionalInterface
    public interface Scope extends AutoCloseable {

        @Override
        void close();
    }
}
