package com.interviewcoach.resume.application.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.interviewcoach.resume.application.event.ResumeAiQuotaReservation;
import com.interviewcoach.resume.application.event.ResumeAiTaskLease;
import com.interviewcoach.resume.application.event.ResumeProfileAnalysisRequestedEvent;
import com.interviewcoach.resume.domain.agent.ResumeProfileAnalysisAgent;
import com.interviewcoach.resume.domain.entity.ResumeProfileAnalysisMode;
import com.interviewcoach.resume.domain.model.ResumeProfileAnalysisData;
import com.interviewcoach.resume.domain.model.UserProfileData;
import com.interviewcoach.resume.infrastructure.redis.ResumeAiQuotaService;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 验证辅助分析 Worker 的任务认领、模式输入、首次调用标记、模型执行、租约结果写回和额度结算顺序。
 *
 * <p>状态服务、分析 Agent 与额度服务均为 Mock；用例区分模型调用前失败、调用后失败、旧任务结果和数据库成功后 Redis 失败。</p>
 */
@ExtendWith(MockitoExtension.class)
class ResumeProfileAnalysisWorkerTest {

    /** 模拟任务认领、首次资格消耗、成功结果和失败状态写回。 */
    @Mock private ResumeProfileAnalysisStateService stateService;
    /** 模拟按 INITIAL、REGENERATE 或 REFINE 模式调用模型生成辅助分析。 */
    @Mock private ResumeProfileAnalysisAgent analysisAgent;
    /** 模拟付费任务调用前计次及成功、失败额度转换。 */
    @Mock private ResumeAiQuotaService quotaService;

    /** 使用上述三个协作者 Mock 构造的被测分析 Worker。 */
    private ResumeProfileAnalysisWorker worker;

    /** 每例重建被测 Worker，确保回调和额度交互只属于当前场景。 */
    @BeforeEach
    void setUp() {
        worker = new ResumeProfileAnalysisWorker(stateService, analysisAgent, quotaService);
    }

    @Test
    void shouldCountOneAttemptAndSuccessForPaidAnalysis() {
        ResumeAiQuotaReservation quota = quota();
        ResumeProfileAnalysisRequestedEvent event = event(quota, null);
        UserProfileData profile = UserProfileData.empty();
        ResumeProfileAnalysisData analysis = new ResumeProfileAnalysisData();
        when(stateService.start(1L, 2L, 3L, "profile-hash"))
                .thenReturn(input(profile, null, ResumeProfileAnalysisMode.REGENERATE, quota));
        doAnswer(invocation -> {
            ((Runnable) invocation.getArgument(4)).run();
            return analysis;
        }).when(analysisAgent).analyze(
                eq(profile), isNull(), isNull(), eq(ResumeProfileAnalysisMode.REGENERATE), any());
        when(quotaService.markAttemptStarted(2L, quota)).thenReturn(true);
        when(stateService.complete(1L, 2L, 3L, "profile-hash", analysis)).thenReturn(true);
        when(quotaService.markSucceeded(2L, quota)).thenReturn(true);

        worker.analyze(event);

        InOrder order = inOrder(quotaService, stateService);
        order.verify(quotaService).markAttemptStarted(2L, quota);
        order.verify(stateService).complete(1L, 2L, 3L, "profile-hash", analysis);
        order.verify(quotaService).markSucceeded(2L, quota);
        verify(quotaService, never()).markFailed(2L, quota);
    }

    @Test
    void shouldMarkInitialCallBeforeModelAndKeepQualificationConsumedAfterFailure() {
        ResumeProfileAnalysisRequestedEvent event = event(null, null);
        UserProfileData profile = UserProfileData.empty();
        when(stateService.start(1L, 2L, 3L, "profile-hash"))
                .thenReturn(input(profile, null, ResumeProfileAnalysisMode.INITIAL, null));
        when(stateService.markInitialModelCallStarted(1L, 2L, 3L, "profile-hash"))
                .thenReturn(true);
        doAnswer(invocation -> {
            ((Runnable) invocation.getArgument(4)).run();
            throw new IllegalArgumentException("invalid response");
        }).when(analysisAgent).analyze(
                eq(profile), isNull(), isNull(), eq(ResumeProfileAnalysisMode.INITIAL), any());

        worker.analyze(event);

        verify(stateService).markInitialModelCallStarted(1L, 2L, 3L, "profile-hash");
        verify(stateService).fail(
                1L, 2L, 3L, "profile-hash", "ANALYSIS_FAILED", "画像分析失败，请稍后重试");
        verify(quotaService, never()).markAttemptStarted(any(), any());
        verify(quotaService, never()).markFailed(any(), any());
    }

    @Test
    void shouldReleaseReservationWithoutAttemptWhenAgentFailsBeforeCallback() {
        ResumeAiQuotaReservation quota = quota();
        ResumeProfileAnalysisRequestedEvent event = event(quota, "关注项目证据");
        UserProfileData profile = UserProfileData.empty();
        when(stateService.start(1L, 2L, 3L, "profile-hash"))
                .thenReturn(input(profile, null, ResumeProfileAnalysisMode.REFINE, quota));
        when(analysisAgent.analyze(
                eq(profile), isNull(), eq("关注项目证据"), eq(ResumeProfileAnalysisMode.REFINE), any()))
                .thenThrow(new IllegalArgumentException("missing retained analysis"));

        worker.analyze(event);

        verify(quotaService, never()).markAttemptStarted(2L, quota);
        verify(quotaService).markFailed(2L, quota);
        verify(stateService).fail(
                1L, 2L, 3L, "profile-hash", "ANALYSIS_FAILED", "画像分析失败，请稍后重试");
    }

    @Test
    void shouldPassRetainedAnalysisAndFeedbackToRefineAgent() {
        ResumeAiQuotaReservation quota = quota();
        ResumeProfileAnalysisData previous = new ResumeProfileAnalysisData();
        ResumeProfileAnalysisData generated = new ResumeProfileAnalysisData();
        UserProfileData profile = UserProfileData.empty();
        ResumeProfileAnalysisRequestedEvent event = event(quota, "关注工程能力");
        when(stateService.start(1L, 2L, 3L, "profile-hash"))
                .thenReturn(input(profile, previous, ResumeProfileAnalysisMode.REFINE, quota));
        doAnswer(invocation -> {
            ((Runnable) invocation.getArgument(4)).run();
            return generated;
        }).when(analysisAgent).analyze(
                eq(profile), eq(previous), eq("关注工程能力"), eq(ResumeProfileAnalysisMode.REFINE), any());
        when(quotaService.markAttemptStarted(2L, quota)).thenReturn(true);
        when(stateService.complete(1L, 2L, 3L, "profile-hash", generated)).thenReturn(true);
        when(quotaService.markSucceeded(2L, quota)).thenReturn(true);

        worker.analyze(event);

        verify(analysisAgent).analyze(
                eq(profile), eq(previous), eq("关注工程能力"), eq(ResumeProfileAnalysisMode.REFINE), any());
    }

    @Test
    void shouldReleaseSuccessReservationWhenLateResultIsRejected() {
        ResumeAiQuotaReservation quota = quota();
        ResumeProfileAnalysisRequestedEvent event = event(quota, null);
        UserProfileData profile = UserProfileData.empty();
        ResumeProfileAnalysisData analysis = new ResumeProfileAnalysisData();
        when(stateService.start(1L, 2L, 3L, "profile-hash"))
                .thenReturn(input(profile, null, ResumeProfileAnalysisMode.REGENERATE, quota));
        doAnswer(invocation -> {
            ((Runnable) invocation.getArgument(4)).run();
            return analysis;
        }).when(analysisAgent).analyze(any(), any(), any(), any(), any());
        when(quotaService.markAttemptStarted(2L, quota)).thenReturn(true);
        when(stateService.complete(1L, 2L, 3L, "profile-hash", analysis)).thenReturn(false);

        worker.analyze(event);

        verify(quotaService).markFailed(2L, quota);
        verify(quotaService, never()).markSucceeded(2L, quota);
    }

    @Test
    void shouldKeepSuccessReservationWhenRedisFailsAfterDatabaseSuccess() {
        ResumeAiQuotaReservation quota = quota();
        ResumeProfileAnalysisRequestedEvent event = event(quota, null);
        UserProfileData profile = UserProfileData.empty();
        ResumeProfileAnalysisData analysis = new ResumeProfileAnalysisData();
        when(stateService.start(1L, 2L, 3L, "profile-hash"))
                .thenReturn(input(profile, null, ResumeProfileAnalysisMode.REGENERATE, quota));
        doAnswer(invocation -> {
            ((Runnable) invocation.getArgument(4)).run();
            return analysis;
        }).when(analysisAgent).analyze(any(), any(), any(), any(), any());
        when(quotaService.markAttemptStarted(2L, quota)).thenReturn(true);
        when(stateService.complete(1L, 2L, 3L, "profile-hash", analysis)).thenReturn(true);
        doThrow(new IllegalStateException("redis unavailable"))
                .when(quotaService).markSucceeded(2L, quota);

        worker.analyze(event);

        verify(quotaService, never()).markFailed(2L, quota);
        verify(stateService, never()).fail(any(), any(), any(), any(), any(), any());
    }

    @Test
    void shouldReleaseEventReservationWhenTaskIsAlreadyStale() {
        ResumeAiQuotaReservation quota = quota();
        ResumeProfileAnalysisRequestedEvent event = event(quota, null);
        when(stateService.start(1L, 2L, 3L, "profile-hash")).thenReturn(null);

        worker.analyze(event);

        verify(quotaService).markFailed(2L, quota);
        verify(analysisAgent, never()).analyze(any(), any(), any(), any(), any());
    }

    private ResumeProfileAnalysisStateService.AnalysisInput input(
            UserProfileData profile,
            ResumeProfileAnalysisData previous,
            ResumeProfileAnalysisMode mode,
            ResumeAiQuotaReservation quota) {
        return new ResumeProfileAnalysisStateService.AnalysisInput(profile, previous, mode, quota);
    }

    private ResumeProfileAnalysisRequestedEvent event(
            ResumeAiQuotaReservation quota, String feedback) {
        return new ResumeProfileAnalysisRequestedEvent(
                1L,
                2L,
                3L,
                "profile-hash",
                new ResumeAiTaskLease("user-permit", "resume-permit"),
                quota,
                feedback,
                true);
    }

    private ResumeAiQuotaReservation quota() {
        return new ResumeAiQuotaReservation(LocalDate.of(2026, 7, 30), "quota-token");
    }
}
