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

@ExtendWith(MockitoExtension.class)
class ResumeTaskRecoveryRunnerTest {

    @Mock private ResumeTaskRecoveryService recoveryService;
    @Mock private ResumeAiQuotaService quotaService;
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
