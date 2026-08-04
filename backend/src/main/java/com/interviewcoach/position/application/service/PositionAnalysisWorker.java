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
 * 在虚拟线程中执行单个岗位模型调用；远程等待不处于数据库事务中。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PositionAnalysisWorker {

    private final JdAnalysisAgent analysisAgent;
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
            CompletionOutcome outcome = stateService.complete(input.taskId(), candidate);
            log.info("[PositionAnalysis] Worker 完成: taskId={}, outcome={}, elapsedMs={}",
                    input.taskId(), outcome, elapsedMillis(startedAt));
            return true;
        } catch (RuntimeException e) {
            log.error("[PositionAnalysis] 候选结果写回失败: taskId={}, errorType={}, elapsedMs={}",
                    input.taskId(), e.getClass().getSimpleName(),
                    elapsedMillis(startedAt), e);
            return recordFailure(
                    input.taskId(),
                    PositionAnalysisFailureCode.UNEXPECTED_ERROR,
                    "岗位解析结果写回失败，请重新解析");
        }
    }

    private boolean recordFailure(
            Long taskId, PositionAnalysisFailureCode failureCode, String errorMessage) {
        try {
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
