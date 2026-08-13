package com.interviewcoach.position.application.service;

import com.interviewcoach.position.application.service.PositionAnalysisStateService.AnalysisInput;
import com.interviewcoach.position.application.service.PositionAnalysisStateService.CompletionOutcome;
import com.interviewcoach.position.domain.agent.JdAnalysisAgent;
import com.interviewcoach.position.domain.exception.PositionAnalysisException;
import com.interviewcoach.position.domain.exception.PositionAnalysisFailureCode;
import com.interviewcoach.position.domain.model.PositionProfileData;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.Locale;
import java.util.concurrent.TimeoutException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 在岗位 Worker 虚拟线程中执行单个模型分析并请求状态服务写回结果。
 * 调度器传入已经从 WAITING 领取为 RUNNING 的不可变输入；远程等待发生在数据库事务外，
 * 返回值告诉调度器数据库状态是否已经收口，从而决定能否释放对应 Redis busy 标记。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PositionAnalysisWorker {

    /** 在 Agent 安全上下文中调用共享 LLM，并把响应解析、校验为候选岗位画像。 */
    private final JdAnalysisAgent analysisAgent;
    /** 以短数据库事务写入成功或失败终态，并处理归档后的迟到结果。 */
    private final PositionAnalysisStateService stateService;

    /**
     * 模型调用真实返回或抛错后才写入 SUCCEEDED/FAILED；本方法不管理本地容量。
     *
     * @return 数据库已确认收口、可以释放该参与者 Redis busy 时返回 true
     */
    public boolean execute(AnalysisInput input) {
        long startedAt = System.nanoTime();
        PositionProfileData candidate;
        try {
            // 在事务外完成 LLM 调用和候选结构校验，避免远程等待占用数据库锁。
            candidate = analysisAgent.analyze(input.jdContent(), input.jobCategory());
        } catch (PositionAnalysisException e) {
            PositionAnalysisFailureCode failureCode = classify(e);
            logAnalysisFailure(input.taskId(), failureCode, e, startedAt);
            return recordFailure(input.taskId(), failureCode, safeMessage(failureCode, e));
        } catch (RuntimeException e) {
            PositionAnalysisFailureCode failureCode = classify(e);
            log.error("[PositionAnalysis] 模型调用出现未预期失败: taskId={}, "
                            + "failureCode={}, errorType={}, elapsedMs={}",
                    input.taskId(), failureCode, e.getClass().getSimpleName(),
                    elapsedMillis(startedAt), e);
            return recordFailure(input.taskId(), failureCode, safeMessage(failureCode, e));
        }

        try {
            // 重新锁定当前 Position 与 Task，只让同一 RUNNING 代次接收本次候选结果。
            CompletionOutcome outcome = stateService.complete(input.taskId(), candidate);
            log.info("[PositionAnalysis] Worker 完成: taskId={}, outcome={}, elapsedMs={}",
                    input.taskId(), outcome, elapsedMillis(startedAt));
            return true;
        } catch (RuntimeException e) {
            log.error("[PositionAnalysis] 候选结果写回失败: taskId={}, errorType={}, elapsedMs={}",
                    input.taskId(), e.getClass().getSimpleName(),
                    elapsedMillis(startedAt), e);
            // 成功结果写回异常后再尝试记录通用失败；若仍无法收口，返回 false 以保留 Redis busy。
            return recordFailure(
                    input.taskId(),
                    PositionAnalysisFailureCode.UNEXPECTED_ERROR,
                    "岗位解析结果写回失败，请重新解析");
        }
    }

    /**
     * 把已分类的失败写入当前 RUNNING 任务。
     *
     * @return 状态服务调用成功时为 {@code true}，包括任务已过期或归档后已删除；数据库调用失败时为
     * {@code false}，调用方必须保留 Redis busy 等待启动恢复
     */
    private boolean recordFailure(
            Long taskId, PositionAnalysisFailureCode failureCode, String errorMessage) {
        try {
            // 失败写回会按当前任务代次和 RUNNING 源状态执行条件更新，迟到 Worker 不覆盖新任务。
            CompletionOutcome outcome = stateService.fail(
                    taskId, failureCode.name(), errorMessage);
            log.info("[PositionAnalysis] Worker 失败状态已收口: taskId={}, outcome={}, failureCode={}",
                    taskId, outcome, failureCode);
            return true;
        } catch (RuntimeException stateError) {
            log.error("[PositionAnalysis] Worker 失败状态写回失败: taskId={}, "
                            + "failureCode={}, errorType={}",
                    taskId, failureCode, stateError.getClass().getSimpleName(), stateError);
            return false;
        }
    }

    /** 按失败类别选择 error 或 warn，日志只记录任务标识、分类、异常类型和耗时，不记录 JD 正文。 */
    private void logAnalysisFailure(
            Long taskId,
            PositionAnalysisFailureCode failureCode,
            PositionAnalysisException error,
            long startedAt) {
        if (failureCode == PositionAnalysisFailureCode.LLM_REQUEST_FAILED
                || failureCode == PositionAnalysisFailureCode.UNEXPECTED_ERROR) {
            log.error("[PositionAnalysis] 模型解析失败: taskId={}, failureCode={}, "
                            + "errorType={}, elapsedMs={}",
                    taskId, failureCode, rootType(error), elapsedMillis(startedAt), error);
            return;
        }
        log.warn("[PositionAnalysis] 模型结果未通过: taskId={}, failureCode={}, "
                        + "errorType={}, elapsedMs={}",
                taskId, failureCode, rootType(error), elapsedMillis(startedAt));
    }

    /**
     * 将中断、常见超时异常和领域异常映射为稳定失败分类；未识别异常归为意外失败。
     */
    private PositionAnalysisFailureCode classify(Throwable error) {
        if (Thread.currentThread().isInterrupted() || hasCause(error, InterruptedException.class)) {
            return PositionAnalysisFailureCode.WORKER_INTERRUPTED;
        }
        if (hasCause(error, TimeoutException.class)
                || hasCause(error, SocketTimeoutException.class)
                || hasCause(error, HttpTimeoutException.class)
                || hasTimeoutType(error)) {
            return PositionAnalysisFailureCode.LLM_TIMEOUT;
        }
        if (error instanceof PositionAnalysisException analysisException) {
            return analysisException.getFailureCode();
        }
        return PositionAnalysisFailureCode.UNEXPECTED_ERROR;
    }

    /** 生成可持久化并返回页面的脱敏说明，不暴露未预期异常和底层模型原文。 */
    private String safeMessage(PositionAnalysisFailureCode failureCode, Throwable error) {
        if (failureCode == PositionAnalysisFailureCode.LLM_TIMEOUT) {
            return "岗位解析调用超时，请重新解析";
        }
        if (failureCode == PositionAnalysisFailureCode.WORKER_INTERRUPTED) {
            return "岗位解析被中断，请重新解析";
        }
        if (error instanceof PositionAnalysisException analysisException
                && analysisException.getMessage() != null
                && !analysisException.getMessage().isBlank()) {
            return analysisException.getMessage();
        }
        return "岗位解析失败，请重新解析";
    }

    /**
     * 最多沿异常原因链检查 20 层以识别指定类型；20 是当前固定扫描上限，精确取值依据缺失。
     * 调大将增加极深异常链的扫描成本，调小可能漏掉更深层原因，移除上限可能受循环原因链影响。
     */
    private boolean hasCause(Throwable error, Class<? extends Throwable> type) {
        Throwable current = error;
        for (int depth = 0; current != null && depth < 20; depth++) {
            if (type.isInstance(current)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    /**
     * 最多检查 20 层异常简单类名中的 timeout 线索；该兼容扫描上限的精确依据缺失。
     * 调小可能漏判供应商包装异常，调大增加极深异常链扫描成本，取消上限可能无法防护异常链环。
     */
    private boolean hasTimeoutType(Throwable error) {
        Throwable current = error;
        for (int depth = 0; current != null && depth < 20; depth++) {
            if (current.getClass().getSimpleName().toLowerCase(Locale.ROOT).contains("timeout")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    /**
     * 最多向下读取 20 层以取得日志中的根异常类型；20 层是当前固定上限，精确依据缺失。
     * 更小上限可能只记录中间包装类型，更大上限增加极深链扫描，移除上限可能受异常链环影响。
     */
    private String rootType(Throwable error) {
        Throwable current = error;
        for (int depth = 0; current.getCause() != null && depth < 20; depth++) {
            current = current.getCause();
        }
        return current.getClass().getSimpleName();
    }

    private long elapsedMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000L;
    }
}
