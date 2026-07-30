package com.interviewcoach.resume.infrastructure.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.interviewcoach.common.exception.BusinessException;
import com.interviewcoach.resume.application.service.ResumeErrorCode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ResumeDailyCreationQuotaServiceTest {

    @Mock private ResumeRedisScriptExecutor scriptExecutor;

    private ResumeDailyCreationQuotaService service;

    @BeforeEach
    void setUp() {
        ResumeAiTaskProperties properties = new ResumeAiTaskProperties();
        ZoneId zone = ZoneId.of("Asia/Shanghai");
        Clock clock = Clock.fixed(Instant.parse("2026-07-30T16:30:00Z"), zone);
        service = new ResumeDailyCreationQuotaService(scriptExecutor, properties, clock);
    }

    @Test
    void shouldUseNextShanghaiDayAfterMidnight() {
        when(scriptExecutor.execute(any(), anyString(), any(String[].class))).thenReturn(1L);

        ResumeDailyCreationQuotaService.CreationReservation reservation = service.reserve(9L);

        assertThat(reservation.quotaDate()).isEqualTo(LocalDate.of(2026, 7, 31));
    }

    @Test
    void shouldRejectSixthDailyCreation() {
        when(scriptExecutor.execute(any(), anyString(), any(String[].class))).thenReturn(-1L);

        assertThatThrownBy(() -> service.reserve(9L))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getCode())
                        .isEqualTo(ResumeErrorCode.DAILY_RESUME_CREATE_LIMIT));
    }

    @Test
    void shouldCommitAndReleaseReservationsThroughLua() {
        ResumeDailyCreationQuotaService.CreationReservation reservation =
                new ResumeDailyCreationQuotaService.CreationReservation(
                        LocalDate.of(2026, 7, 31), "create-token");
        when(scriptExecutor.execute(any(), anyString(), any(String[].class)))
                .thenReturn(2L)
                .thenReturn(1L)
                .thenReturn(2L);

        assertThat(service.commit(9L, reservation)).isTrue();
        assertThat(service.release(9L, reservation)).isTrue();
        assertThat(service.release(9L, reservation)).isTrue();
    }
}
