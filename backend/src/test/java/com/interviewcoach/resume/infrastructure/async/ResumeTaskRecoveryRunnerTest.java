package com.interviewcoach.resume.infrastructure.async;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.interviewcoach.resume.application.event.ResumeAiQuotaReservation;
import com.interviewcoach.resume.application.service.ResumeTaskRecoveryService;
import com.interviewcoach.resume.infrastructure.redis.ResumeAiQuotaService;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.ApplicationArguments;

/**
 * 验证当前进程启动恢复 Runner 按“数据库标记中断任务、Redis 额度结算、数据库凭据清理”顺序处理恢复结果。
 *
 * <p>服务均为 Mock；只有 Redis 确认释放的凭据才清理，本类不证明部署为单实例或 Redis 状态跨实例共享。</p>
 */
@ExtendWith(MockitoExtension.class)
class ResumeTaskRecoveryRunnerTest {

    /** 模拟扫描中断任务并清理已结算额度凭据的数据库恢复服务。 */
    @Mock private ResumeTaskRecoveryService recoveryService;
    /** 模拟把持久化额度 token 在 Redis 中转换为失败。 */
    @Mock private ResumeAiQuotaService quotaService;
    /** 满足 ApplicationRunner 回调签名的启动参数 Mock；被测流程不读取其内容。 */
    @Mock private ApplicationArguments arguments;

    @Test
    void shouldClearOnlyQuotaMetadataConfirmedReleasedByRedis() {
        ResumeTaskRecoveryService.ParseQuotaRecovery parseRecovery =
                new ResumeTaskRecoveryService.ParseQuotaRecovery(
                        1L,
                        2L,
                        3L,
                        new ResumeAiQuotaReservation(
                                LocalDate.of(2026, 7, 30), "parse-token"));
        ResumeTaskRecoveryService.AnalysisQuotaRecovery analysisRecovery =
                new ResumeTaskRecoveryService.AnalysisQuotaRecovery(
                        4L,
                        2L,
                        5L,
                        "profile-hash",
                        new ResumeAiQuotaReservation(
                                LocalDate.of(2026, 7, 30), "analysis-token"));
        when(recoveryService.failInterruptedTasks()).thenReturn(
                new ResumeTaskRecoveryService.RecoveryResult(
                        1, 1, List.of(parseRecovery), List.of(analysisRecovery)));
        when(quotaService.markFailed(2L, parseRecovery.reservation())).thenReturn(true);
        when(quotaService.markFailed(2L, analysisRecovery.reservation())).thenReturn(false);
        ResumeTaskRecoveryRunner runner =
                new ResumeTaskRecoveryRunner(recoveryService, quotaService);

        runner.run(arguments);

        verify(recoveryService).clearRecoveredParseQuota(parseRecovery);
        verify(recoveryService, never()).clearRecoveredAnalysisQuota(analysisRecovery);
    }
}
