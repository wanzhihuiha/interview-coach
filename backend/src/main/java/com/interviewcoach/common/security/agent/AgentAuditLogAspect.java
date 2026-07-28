package com.interviewcoach.common.security.agent;

import java.lang.reflect.Method;
import java.time.Duration;
import java.time.Instant;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.core.annotation.Order;

/**
 * Agent 调用审计切面。
 *
 * <p>拦截所有带 {@link AgentAuditLog} 注解的方法，或所有带 {@link AgentPermission} 注解的方法，
 * 记录调用者、目标、耗时与结果。通过 {@code @Order} 控制该切面在权限切面之外执行，
 * 从而统一捕获允许/拒绝两种结果。</p>
 */
@Slf4j
@Aspect
@Order(AgentAuditLogAspect.AUDIT_ORDER)
public class AgentAuditLogAspect {

    /**
     * 审计切面优先级，值越小优先级越高。
     * 必须高于权限切面，才能完整包裹权限校验过程。
     */
    public static final int AUDIT_ORDER = 100;

    private final AgentPermissionProperties properties;
    private final AgentAuditLogStorageService storageService;

    public AgentAuditLogAspect(AgentPermissionProperties properties,
                               AgentAuditLogStorageService storageService) {
        this.properties = properties;
        this.storageService = storageService;
    }

    @Around("@annotation(agentAuditLog)")
    public Object around(ProceedingJoinPoint pjp, AgentAuditLog agentAuditLog) throws Throwable {
        return doAudit(pjp, agentAuditLog.value(), agentAuditLog.logArgs(), agentAuditLog.logResult());
    }

    @Around("@annotation(com.interviewcoach.common.security.agent.AgentPermission)")
    public Object aroundPermission(ProceedingJoinPoint pjp) throws Throwable {
        return doAudit(pjp, "", false, false);
    }

    private Object doAudit(ProceedingJoinPoint pjp, String operation,
                           boolean logArgs, boolean logResult) throws Throwable {
        MethodSignature signature = (MethodSignature) pjp.getSignature();
        Method method = signature.getMethod();
        String methodKey = buildMethodKey(method);
        String opName = operation.isBlank() ? methodKey : operation;
        AgentType caller = AgentContextHolder.current();

        Instant start = Instant.now();
        try {
            Object result = pjp.proceed();
            long durationMs = Duration.between(start, Instant.now()).toMillis();
            String argsSummary = logArgs ? summarizeArgs(pjp.getArgs()) : null;
            String resultSummary = logResult ? summarizeResult(result) : null;
            if (properties.isAuditLog()) {
                log.info("[AgentAudit] allowed caller={} operation={} method={} durationMs={} args={} result={}",
                        caller, opName, methodKey, durationMs, argsSummary, resultSummary);
            }
            persistLog(caller, opName, methodKey, AgentAuditLogRecord.AuditStatus.ALLOWED,
                    durationMs, argsSummary, resultSummary, null);
            return result;
        } catch (AgentAccessDeniedException e) {
            long durationMs = Duration.between(start, Instant.now()).toMillis();
            if (properties.isDenyLog() || properties.isAuditLog()) {
                log.warn("[AgentAudit] denied caller={} operation={} method={} durationMs={} reason={}",
                        caller, opName, methodKey, durationMs, e.getMessage());
            }
            persistLog(caller, opName, methodKey, AgentAuditLogRecord.AuditStatus.DENIED,
                    durationMs, logArgs ? summarizeArgs(pjp.getArgs()) : null, null,
                    truncate(e.getMessage(), 2000));
            throw e;
        } catch (Throwable t) {
            long durationMs = Duration.between(start, Instant.now()).toMillis();
            if (properties.isAuditLog()) {
                log.error("[AgentAudit] failed caller={} operation={} method={} durationMs={} error={}",
                        caller, opName, methodKey, durationMs, t.getMessage(), t);
            }
            persistLog(caller, opName, methodKey, AgentAuditLogRecord.AuditStatus.FAILED,
                    durationMs, logArgs ? summarizeArgs(pjp.getArgs()) : null, null,
                    truncate(t.getMessage(), 2000));
            throw t;
        }
    }

    private void persistLog(AgentType caller, String operation, String methodKey,
                            AgentAuditLogRecord.AuditStatus status, long durationMs,
                            String argsSummary, String resultSummary, String errorMessage) {
        if (storageService == null) {
            return;
        }
        AgentAuditLogRecord record = new AgentAuditLogRecord();
        record.setCaller(caller);
        record.setOperation(operation);
        record.setMethodKey(methodKey);
        record.setStatus(status);
        record.setDurationMs(durationMs);
        record.setArgsSummary(argsSummary);
        record.setResultSummary(resultSummary);
        record.setErrorMessage(errorMessage);
        storageService.save(record);
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    private String buildMethodKey(Method method) {
        return method.getDeclaringClass().getSimpleName() + "." + method.getName();
    }

    private String summarizeArgs(Object[] args) {
        if (args == null || args.length == 0) {
            return "[]";
        }
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < args.length; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(args[i] == null ? "null" : args[i].getClass().getSimpleName());
        }
        sb.append("]");
        return sb.toString();
    }

    private String summarizeResult(Object result) {
        if (result == null) {
            return "null";
        }
        return result.getClass().getSimpleName();
    }
}
