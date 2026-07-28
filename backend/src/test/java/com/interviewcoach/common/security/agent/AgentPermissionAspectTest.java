package com.interviewcoach.common.security.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Agent 权限边界切面单元测试。
 */
class AgentPermissionAspectTest {

    private AgentPermissionProperties properties;
    private AgentPermissionAspect aspect;

    @BeforeEach
    void setUp() {
        properties = new AgentPermissionProperties();
        properties.setEnabled(true);
        properties.setAuditLog(false);
        properties.setDenyLog(false);
        aspect = new AgentPermissionAspect(properties, Collections.emptyList());
        AgentContextHolder.reset();
    }

    @AfterEach
    void tearDown() {
        AgentContextHolder.reset();
    }

    @Test
    void shouldAllowAgentInAnnotation() throws Throwable {
        AgentContextHolder.enter(AgentType.INTERVIEWER);

        ProceedingJoinPoint pjp = mockJoinPoint("allowedMethod");
        AgentPermission annotation = createAnnotation(AgentType.INTERVIEWER);

        Object result = aspect.around(pjp, annotation);
        assertEquals("ok", result);
    }

    @Test
    void shouldDenyAgentNotInAnnotation() throws Throwable {
        AgentContextHolder.enter(AgentType.COACH);

        ProceedingJoinPoint pjp = mockJoinPoint("deniedMethod");
        AgentPermission annotation = createAnnotation(AgentType.INTERVIEWER);

        assertThrows(AgentAccessDeniedException.class, () -> aspect.around(pjp, annotation));
    }

    @Test
    void shouldDenyWhenNoAgentContext() throws Throwable {
        ProceedingJoinPoint pjp = mockJoinPoint("noContextMethod");
        AgentPermission annotation = createAnnotation(AgentType.INTERVIEWER);

        assertThrows(AgentAccessDeniedException.class, () -> aspect.around(pjp, annotation));
    }

    @Test
    void shouldAllowAgentConfiguredInYaml() throws Throwable {
        properties.setRules(Collections.singletonMap("Tool.yamlMethod", List.of("EVALUATOR")));
        AgentContextHolder.enter(AgentType.EVALUATOR);

        ProceedingJoinPoint pjp = mockJoinPoint("yamlMethod");
        AgentPermission annotation = createAnnotation(AgentType.INTERVIEWER);

        Object result = aspect.around(pjp, annotation);
        assertEquals("ok", result);
    }

    @Test
    void shouldDenyAgentBlockedByYaml() throws Throwable {
        properties.setRules(Collections.singletonMap("Tool.yamlMethod", List.of("EVALUATOR")));
        AgentContextHolder.enter(AgentType.INTERVIEWER);

        ProceedingJoinPoint pjp = mockJoinPoint("yamlMethod");
        AgentPermission annotation = createAnnotation(AgentType.INTERVIEWER, AgentType.EVALUATOR);

        assertThrows(AgentAccessDeniedException.class, () -> aspect.around(pjp, annotation));
    }

    @Test
    void shouldSkipCheckWhenDisabled() throws Throwable {
        properties.setEnabled(false);

        ProceedingJoinPoint pjp = mockJoinPoint("disabledMethod");
        AgentPermission annotation = createAnnotation(AgentType.INTERVIEWER);

        Object result = aspect.around(pjp, annotation);
        assertEquals("ok", result);
    }

    private ProceedingJoinPoint mockJoinPoint(String methodName) throws Throwable {
        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        MethodSignature signature = mock(MethodSignature.class);
        Method method = Tool.class.getMethod(methodName);

        when(pjp.getSignature()).thenReturn(signature);
        when(signature.getMethod()).thenReturn(method);
        when(pjp.getTarget()).thenReturn(new Tool());
        when(pjp.getArgs()).thenReturn(new Object[]{});
        when(pjp.proceed()).thenReturn("ok");
        return pjp;
    }

    private AgentPermission createAnnotation(AgentType... allowed) {
        return new AgentPermission() {
            @Override
            public Class<? extends java.lang.annotation.Annotation> annotationType() {
                return AgentPermission.class;
            }

            @Override
            public AgentType[] value() {
                return allowed;
            }
        };
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

        public String noContextMethod() {
            return "ok";
        }

        public String yamlMethod() {
            return "ok";
        }

        public String disabledMethod() {
            return "ok";
        }
    }
}
