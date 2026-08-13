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
 * 当前进程完成 Spring 装配后的简历任务恢复入口；将启动扫描命中的数据库中间态任务标记失败并尝试处理额度，
 * 不重放模型调用。源码未限制单实例，也无法确认部署的 Redis 重启或持久化策略。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ResumeTaskRecoveryRunner implements ApplicationRunner {

    /** 批量失败遗留数据库任务并提供逐条额度恢复上下文。 */
    private final ResumeTaskRecoveryService recoveryService;
    /** 根据数据库已确认终态推进 Redis 额度 token。 */
    private final ResumeAiQuotaService quotaService;

    /**
     * Spring 完成装配后执行一次恢复扫描，不创建长期调度任务。
     */
    @Override
    public void run(ApplicationArguments args) {
        // 先在数据库中终止当前进程启动时发现的遗留任务，再依据终态处理其 Redis 凭据。
        ResumeTaskRecoveryService.RecoveryResult result = recoveryService.failInterruptedTasks();
        int settledQuotaCount = 0;
        int pendingQuotaCount = 0;
        for (ResumeTaskRecoveryService.ParseQuotaRecovery recovery : result.parseQuotaRecoveries()) {
            // 逐条结算事实解析额度；失败项保留数据库凭据，供后续启动再次恢复。
            if (releaseParseQuota(recovery)) {
                settledQuotaCount++;
            } else {
                pendingQuotaCount++;
            }
        }
        for (ResumeTaskRecoveryService.AnalysisQuotaRecovery recovery : result.analysisQuotaRecoveries()) {
            // 逐条结算辅助分析额度；失败项同样保留凭据，不伪装恢复完成。
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

    /** 按事实解析数据库终态推进同日 Redis token 或跳过已关闭日期，并仅在可清理时删除数据库凭据。 */
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

    /** 按辅助分析数据库终态推进同日 Redis token 或跳过已关闭日期，并仅在可清理时删除数据库凭据。 */
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
