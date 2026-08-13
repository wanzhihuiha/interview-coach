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
 * 验证 Agent 权限切面的线程身份、注解允许列表、YAML 覆盖规则和总开关边界。
 *
 * <p>测试直接调用切面并使用空动态评估器集合，因而不覆盖具体 {@link PermissionEvaluator} 的业务判断。</p>
 */
class AgentPermissionAspectTest {

    /** 提供总开关及按目标方法覆盖注解允许列表的测试配置。 */
    private AgentPermissionProperties properties;
    /** 使用测试配置和空动态评估器集合构造的被测权限切面。 */
    private AgentPermissionAspect aspect;

    /** 每例重建权限配置和被测切面，并清空当前线程可能遗留的 Agent 身份。 */
    @BeforeEach
    void setUp() {
        properties = new AgentPermissionProperties();
        properties.setEnabled(true);
        properties.setAuditLog(false);
        properties.setDenyLog(false);
        aspect = new AgentPermissionAspect(properties, Collections.emptyList());
        AgentContextHolder.reset();
    }

    /** 每例结束后清理 ThreadLocal Agent 身份，避免调用者上下文串入后续测试。 */
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
     * 为切面生成与 YAML 规则一致的方法键，并向 JoinPoint Mock 提供目标方法元数据。
     *
     * <p>方法本身不承载权限逻辑，是否允许执行只由被测切面和模拟调用者上下文决定。</p>
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
