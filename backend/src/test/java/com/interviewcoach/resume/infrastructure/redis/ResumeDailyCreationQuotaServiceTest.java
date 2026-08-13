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

/**
 * 验证简历每日创建额度的上海业务日期、超限返回码以及预留 token 提交与释放状态。
 *
 * <p>Redis 脚本执行器为 Mock；默认“每天五份”只通过返回码场景体现，具体上限仍来自可配置属性。</p>
 */
@ExtendWith(MockitoExtension.class)
class ResumeDailyCreationQuotaServiceTest {

    /** 模拟每日创建额度 Lua 脚本执行，不连接真实 Redis。 */
    @Mock private ResumeRedisScriptExecutor scriptExecutor;

    /** 使用脚本 Mock、默认配置和固定时钟构造的被测创建额度服务。 */
    private ResumeDailyCreationQuotaService service;

    /** 每例固定上海时区午夜后的时刻，确保额度日期可重复断言。 */
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
