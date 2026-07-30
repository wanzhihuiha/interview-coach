package com.interviewcoach.resume.infrastructure.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.interviewcoach.common.exception.BusinessException;
import com.interviewcoach.resume.application.event.ResumeAiQuotaReservation;
import com.interviewcoach.resume.application.service.ResumeErrorCode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ResumeAiQuotaServiceTest {

    @Mock private ResumeRedisScriptExecutor scriptExecutor;

    private ResumeAiQuotaService service;

    @BeforeEach
    void setUp() {
        ResumeAiTaskProperties properties = new ResumeAiTaskProperties();
        ZoneId zone = ZoneId.of("Asia/Shanghai");
        Clock clock = Clock.fixed(Instant.parse("2026-07-30T15:30:00Z"), zone);
        service = new ResumeAiQuotaService(scriptExecutor, properties, clock);
    }

    @Test
    void shouldFixQuotaDateAtShanghaiAdmissionDay() {
        when(scriptExecutor.execute(any(), anyString(), any(String[].class))).thenReturn(1L);

        ResumeAiQuotaReservation reservation = service.reserve(7L);

        assertThat(reservation.quotaDate()).isEqualTo(LocalDate.of(2026, 7, 30));
        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(scriptExecutor).execute(any(), key.capture(), any(String[].class));
        assertThat(key.getValue()).endsWith(":quota:user:7:date:2026-07-30");
    }

    @Test
    void shouldDistinguishSuccessAndAttemptLimits() {
        when(scriptExecutor.execute(any(), anyString(), any(String[].class)))
                .thenReturn(-1L)
                .thenReturn(-2L);

        assertThatThrownBy(() -> service.reserve(7L))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getCode())
                        .isEqualTo(ResumeErrorCode.AI_DAILY_SUCCESS_LIMIT));
        assertThatThrownBy(() -> service.reserve(7L))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getCode())
                        .isEqualTo(ResumeErrorCode.AI_DAILY_ATTEMPT_LIMIT));
    }

    @Test
    void shouldTreatRepeatedTransitionsAsIdempotent() {
        ResumeAiQuotaReservation reservation =
                new ResumeAiQuotaReservation(LocalDate.of(2026, 7, 30), "quota-token");
        when(scriptExecutor.execute(any(), anyString(), any(String[].class)))
                .thenReturn(2L)
                .thenReturn(2L)
                .thenReturn(2L);

        assertThat(service.markAttemptStarted(7L, reservation)).isTrue();
        assertThat(service.markSucceeded(7L, reservation)).isTrue();
        assertThat(service.markFailed(7L, reservation)).isTrue();
    }
}
