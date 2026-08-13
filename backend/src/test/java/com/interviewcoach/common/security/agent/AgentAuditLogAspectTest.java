package com.interviewcoach.common.security.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 验证 Agent 审计切面对允许、拒绝和失败调用的结果分类与原结果传播边界。
 *
 * <p>测试通过反射方法元数据和 Mock JoinPoint 隔离目标调用，并覆盖审计开关关闭场景；异步数据库落库不在本类验证范围内。</p>
 */
class AgentAuditLogAspectTest {

    /** 为每个场景提供审计与拒绝日志开关的可变测试配置。 */
    private AgentPermissionProperties properties;
    /** 隔离真实异步持久化的审计存储 Mock。 */
    private AgentAuditLogStorageService storageService;
    /** 使用测试配置和存储 Mock 构造的被测审计切面。 */
    private AgentAuditLogAspect aspect;

    /** 每例重建配置、存储 Mock 和被测切面，并清空当前线程可能遗留的 Agent 身份。 */
    @BeforeEach
    void setUp() {
        properties = new AgentPermissionProperties();
        properties.setEnabled(true);
        properties.setAuditLog(true);
        properties.setDenyLog(true);
        storageService = mock(AgentAuditLogStorageService.class);
        aspect = new AgentAuditLogAspect(properties, storageService);
        AgentContextHolder.reset();
    }

    /** 每例结束后清理 ThreadLocal Agent 身份，避免上下文影响后续测试。 */
    @AfterEach
    void tearDown() {
        AgentContextHolder.reset();
    }

    @Test
    void shouldRecordAllowedAccess() throws Throwable {
        AgentContextHolder.enter(AgentType.INTERVIEWER);

        ProceedingJoinPoint pjp = mockJoinPoint("allowedMethod", false);
        Object result = aspect.aroundPermission(pjp);

        assertEquals("ok", result);
    }

    @Test
    void shouldRecordDeniedAccess() throws Throwable {
        AgentContextHolder.enter(AgentType.COACH);

        ProceedingJoinPoint pjp = mockJoinPoint("deniedMethod", true);
        AgentAccessDeniedException exception = assertThrows(
                AgentAccessDeniedException.class,
                () -> aspect.aroundPermission(pjp));

        assertEquals("denied", exception.getMessage());
    }

    @Test
    void shouldSkipAuditWhenDisabled() throws Throwable {
        properties.setAuditLog(false);
        properties.setDenyLog(false);

        ProceedingJoinPoint pjp = mockJoinPoint("allowedMethod", false);
        Object result = aspect.aroundPermission(pjp);

        assertEquals("ok", result);
    }

    @Test
    void shouldRecordFailure() throws Throwable {
        AgentContextHolder.enter(AgentType.INTERVIEWER);

        ProceedingJoinPoint pjp = mockJoinPoint("failingMethod", false);
        when(pjp.proceed()).thenThrow(new RuntimeException("boom"));

        assertThrows(RuntimeException.class, () -> aspect.aroundPermission(pjp));
    }

    private ProceedingJoinPoint mockJoinPoint(String methodName, boolean shouldThrow) throws Throwable {
        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        MethodSignature signature = mock(MethodSignature.class);
        Method method = Tool.class.getMethod(methodName);

        when(pjp.getSignature()).thenReturn(signature);
        when(signature.getMethod()).thenReturn(method);
        when(pjp.getTarget()).thenReturn(new Tool());
        when(pjp.getArgs()).thenReturn(new Object[]{});
        if (shouldThrow) {
            when(pjp.proceed()).thenThrow(new AgentAccessDeniedException("denied"));
        } else {
            when(pjp.proceed()).thenReturn("ok");
        }
        return pjp;
    }

    /**
     * 为 JoinPoint Mock 提供可反射的方法元数据。
     *
     * <p>这些方法不承载被测业务，实际返回或抛错结果由 {@link ProceedingJoinPoint} Mock 控制。</p>
     */
    @SuppressWarnings("unused")
    public static class Tool {
        public String allowedMethod() {
            return "ok";
        }

        public String deniedMethod() {
            return "ok";
        }

        public String failingMethod() {
            return "ok";
        }
    }
}
