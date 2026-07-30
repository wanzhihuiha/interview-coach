package com.interviewcoach.resume.infrastructure.async;

import com.interviewcoach.resume.application.service.ResumeAiQuotaSettlement;
import com.interviewcoach.resume.application.service.ResumeTaskRecoveryService;
import com.interviewcoach.resume.infrastructure.redis.ResumeAiQuotaService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 应用启动恢复入口，不自动重放外部模型调用。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ResumeTaskRecoveryRunner implements ApplicationRunner {

    private final ResumeTaskRecoveryService recoveryService;
    private final ResumeAiQuotaService quotaService;

    /**
     * Spring 完成装配后执行一次恢复扫描，不创建长期调度任务。
     */
    @Override
    public void run(ApplicationArguments args) {
        ResumeTaskRecoveryService.RecoveryResult result = recoveryService.failInterruptedTasks();
        int settledQuotaCount = 0;
        int pendingQuotaCount = 0;
        for (ResumeTaskRecoveryService.ParseQuotaRecovery recovery : result.parseQuotaRecoveries()) {
            if (releaseParseQuota(recovery)) {
                settledQuotaCount++;
            } else {
                pendingQuotaCount++;
            }
        }
        for (ResumeTaskRecoveryService.AnalysisQuotaRecovery recovery : result.analysisQuotaRecoveries()) {
            if (releaseAnalysisQuota(recovery)) {
                settledQuotaCount++;
            } else {
                pendingQuotaCount++;
            }
        }
        if (result.parseCount() > 0 || result.analysisCount() > 0 || pendingQuotaCount > 0) {
            log.warn(
                    "[ResumeRecovery] 遗留任务恢复完成: parseCount={}, analysisCount={}, "
                            + "settledQuotaCount={}, pendingQuotaCount={}",
                    result.parseCount(),
                    result.analysisCount(),
                    settledQuotaCount,
                    pendingQuotaCount);
        }
    }

    private boolean releaseParseQuota(ResumeTaskRecoveryService.ParseQuotaRecovery recovery) {
        try {
            return ResumeAiQuotaSettlement.settle(
                    quotaService,
                    recovery.userId(),
                    recovery.reservation(),
                    recovery.outcome(),
                    () -> recoveryService.clearRecoveredParseQuota(recovery));
        } catch (RuntimeException e) {
            log.warn(
                    "[ResumeRecovery] 事实解析额度结算恢复失败: resumeId={}, outcome={}, errorType={}",
                    recovery.resumeId(),
                    recovery.outcome(),
                    e.getClass().getSimpleName());
            return false;
        }
    }

    private boolean releaseAnalysisQuota(ResumeTaskRecoveryService.AnalysisQuotaRecovery recovery) {
        try {
            return ResumeAiQuotaSettlement.settle(
                    quotaService,
                    recovery.userId(),
                    recovery.reservation(),
                    recovery.outcome(),
                    () -> recoveryService.clearRecoveredAnalysisQuota(recovery));
        } catch (RuntimeException e) {
            log.warn(
                    "[ResumeRecovery] 辅助分析额度结算恢复失败: resumeId={}, outcome={}, errorType={}",
                    recovery.resumeId(),
                    recovery.outcome(),
                    e.getClass().getSimpleName());
            return false;
        }
    }
}
