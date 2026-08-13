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
 * <p>Spring AOP 调用带 {@link AgentAuditLog} 方法注解或 {@link AgentPermission} 方法注解时，
 * 本切面记录调用者、目标、耗时与允许、拒绝、失败三类结果。通过 {@code @Order} 让它包裹
 * 权限切面，从而在目标方法未执行的拒绝场景中也能产生审计记录。</p>
 */
@Slf4j
@Aspect
@Order(AgentAuditLogAspect.AUDIT_ORDER)
public class AgentAuditLogAspect {

    /**
     * 审计切面优先级，值越小优先级越高。
     * 必须小于权限切面的 200，才能完整包裹权限校验过程；100 是当前固定排序值，精确依据缺失。
     */
    public static final int AUDIT_ORDER = 100;

    /** 控制允许、拒绝和失败应用日志开关的 Agent 权限配置。 */
    private final AgentPermissionProperties properties;
    /** 将审计实体交给代理式异步新事务保存的存储服务。 */
    private final AgentAuditLogStorageService storageService;

    public AgentAuditLogAspect(AgentPermissionProperties properties,
                               AgentAuditLogStorageService storageService) {
        this.properties = properties;
        this.storageService = storageService;
    }

    /**
     * 审计显式标注 {@link AgentAuditLog} 的方法，并使用注解中的操作名和摘要开关。
     */
    @Around("@annotation(agentAuditLog)")
    public Object around(ProceedingJoinPoint pjp, AgentAuditLog agentAuditLog) throws Throwable {
        return doAudit(pjp, agentAuditLog.value(), agentAuditLog.logArgs(), agentAuditLog.logResult());
    }

    /**
     * 审计所有需要 {@link AgentPermission} 的方法；即使没有显式审计注解也记录权限结果。
     */
    @Around("@annotation(com.interviewcoach.common.security.agent.AgentPermission)")
    public Object aroundPermission(ProceedingJoinPoint pjp) throws Throwable {
        return doAudit(pjp, "", false, false);
    }

    /**
     * 执行目标调用并按结果分类：正常返回为 ALLOWED，权限拒绝为 DENIED，其他异常为 FAILED。
     *
     * <p>存储方法内部的数据库异常会被隔离，不替换目标结果或异常；异步任务若尚未进入存储方法
     * 就在调度阶段失败，当前切面没有单独捕获该调度异常。</p>
     */
    private Object doAudit(ProceedingJoinPoint pjp, String operation,
                           boolean logArgs, boolean logResult) throws Throwable {
        MethodSignature signature = (MethodSignature) pjp.getSignature();
        Method method = signature.getMethod();
        String methodKey = buildMethodKey(method);
        String opName = operation.isBlank() ? methodKey : operation;
        AgentType caller = AgentContextHolder.current();

        Instant start = Instant.now();
        try {
            // 执行内层权限切面和目标方法，返回值保持原样交回调用方。
            Object result = pjp.proceed();
            long durationMs = Duration.between(start, Instant.now()).toMillis();
            String argsSummary = logArgs ? summarizeArgs(pjp.getArgs()) : null;
            String resultSummary = logResult ? summarizeResult(result) : null;
            if (properties.isAuditLog()) {
                log.info("[AgentAudit] allowed caller={} operation={} method={} durationMs={} args={} result={}",
                        caller, opName, methodKey, durationMs, argsSummary, resultSummary);
            }
            // 正常调用按 ALLOWED 异步落库；进入存储方法后的数据库失败不会改写本次方法返回值。
            persistLog(caller, opName, methodKey, AgentAuditLogRecord.AuditStatus.ALLOWED,
                    durationMs, argsSummary, resultSummary, null);
            return result;
        } catch (AgentAccessDeniedException e) {
            long durationMs = Duration.between(start, Instant.now()).toMillis();
            if (properties.isDenyLog() || properties.isAuditLog()) {
                log.warn("[AgentAudit] denied caller={} operation={} method={} durationMs={} reason={}",
                        caller, opName, methodKey, durationMs, e.getMessage());
            }
            // 权限切面已阻止目标方法执行；这里只保存拒绝摘要，随后继续抛出原拒绝异常。
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
            // 非权限异常按 FAILED 保存，随后保留原异常类型和堆栈继续向上游传播。
            persistLog(caller, opName, methodKey, AgentAuditLogRecord.AuditStatus.FAILED,
                    durationMs, logArgs ? summarizeArgs(pjp.getArgs()) : null, null,
                    truncate(t.getMessage(), 2000));
            throw t;
        }
    }

    /**
     * 组装审计实体并交给异步存储服务；测试或手工构造未提供存储服务时跳过持久化。
     */
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
        // Spring 代理负责异步调度和新事务；任务进入存储方法后，数据库异常会被内部吸收。
        storageService.save(record);
    }

    /**
     * 按目标列长度截断错误摘要；当前拒绝和失败消息使用审计表 {@code error_message} 的 2000 字符上限。
     */
    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    /** 返回用于配置、日志和审计查询的 {@code 声明类简单名.方法名}。 */
    private String buildMethodKey(Method method) {
        return method.getDeclaringClass().getSimpleName() + "." + method.getName();
    }

    /** 只汇总参数运行时类型，不调用参数 {@code toString()}，避免正文进入通用审计日志。 */
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

    /** 只返回结果运行时类型名；结果为空时返回字面量 {@code null}。 */
    private String summarizeResult(Object result) {
        if (result == null) {
            return "null";
        }
        return result.getClass().getSimpleName();
    }
}
