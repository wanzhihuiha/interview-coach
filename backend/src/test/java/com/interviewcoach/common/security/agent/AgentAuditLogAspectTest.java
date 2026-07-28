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
 * Agent 审计切面单元测试。
 */
class AgentAuditLogAspectTest {

    private AgentPermissionProperties properties;
    private AgentAuditLogStorageService storageService;
    private AgentAuditLogAspect aspect;

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
     * 测试用的目标类。
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
