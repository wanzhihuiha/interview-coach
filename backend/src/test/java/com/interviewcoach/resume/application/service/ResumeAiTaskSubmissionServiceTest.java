package com.interviewcoach.resume.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.interviewcoach.common.exception.BusinessException;
import com.interviewcoach.resume.application.dto.ResumeProfileAnalysisRetryRequest;
import com.interviewcoach.resume.application.event.ResumeAiQuotaReservation;
import com.interviewcoach.resume.application.event.ResumeAiTaskLease;
import com.interviewcoach.resume.application.event.ResumeProfileAnalysisRequestedEvent;
import com.interviewcoach.resume.application.service.ResumePersistenceService.ConfirmedResume;
import com.interviewcoach.resume.application.service.ResumeProfileAnalysisStateService.RetryPlan;
import com.interviewcoach.resume.domain.entity.ResumeProfileAnalysisMode;
import com.interviewcoach.resume.infrastructure.redis.ResumeAiQuotaService;
import com.interviewcoach.resume.infrastructure.redis.ResumeAiTaskAdmissionService;
import com.interviewcoach.resume.infrastructure.redis.ResumeAiTaskLeaseRunner;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

@ExtendWith(MockitoExtension.class)
class ResumeAiTaskSubmissionServiceTest {

    @Mock private ResumeParseStateService parseStateService;
    @Mock private ResumeProfileAnalysisStateService analysisStateService;
    @Mock private ResumeAiTaskAdmissionService admissionService;
    @Mock private ResumeAiTaskLeaseRunner leaseRunner;
    @Mock private ResumeAiQuotaService quotaService;
    @Mock private ApplicationEventPublisher eventPublisher;

    private ResumeAiTaskSubmissionService service;

    @BeforeEach
    void setUp() {
        service = new ResumeAiTaskSubmissionService(
                parseStateService,
                analysisStateService,
                admissionService,
                leaseRunner,
                quotaService,
                eventPublisher);
    }

    @Test
    void shouldRejectMissingPrivateOrUnknownModeBeforeAnySideEffect() {
        assertInvalid(null);
        assertInvalid(new ResumeProfileAnalysisRetryRequest(null, null));
        assertInvalid(new ResumeProfileAnalysisRetryRequest("INITIAL", null));
        assertInvalid(new ResumeProfileAnalysisRetryRequest("regenerate", null));

        verifyNoInteractions(admissionService, quotaService, eventPublisher);
    }

    @Test
    void shouldRejectBlankOrOversizedRefineFeedbackBeforeAnySideEffect() {
        assertInvalid(new ResumeProfileAnalysisRetryRequest("REFINE", "   "));
        assertInvalid(new ResumeProfileAnalysisRetryRequest("REFINE", "x".repeat(1001)));

        verifyNoInteractions(admissionService, quotaService, eventPublisher);
    }

    @Test
    void shouldDiscardRegenerateFeedbackAndRouteUnusedFreeQualificationToInitial() {
        ResumeAiTaskLease lease = lease();
        ResumeProfileAnalysisRequestedEvent prepared = event(
                ResumeProfileAnalysisMode.INITIAL, null, null);
        when(analysisStateService.previewRetry(2L, 1L, ResumeProfileAnalysisMode.REGENERATE))
                .thenReturn(new RetryPlan(ResumeProfileAnalysisMode.INITIAL, false));
        when(admissionService.acquire(1L, 2L)).thenReturn(lease);
        when(analysisStateService.prepareRetry(
                2L,
                1L,
                ResumeProfileAnalysisMode.REGENERATE,
                ResumeProfileAnalysisMode.INITIAL,
                null,
                null))
                .thenReturn(prepared);

        service.submitManualAnalysis(
                1L, 2L, new ResumeProfileAnalysisRetryRequest("REGENERATE", "x".repeat(2000)));

        verify(quotaService, never()).reserve(1L);
        verify(eventPublisher).publishEvent(prepared.withTaskLease(lease));
    }

    @Test
    void shouldAcquirePermitThenReserveQuotaAndRegisterRefineWithTrimmedFeedback() {
        ResumeAiTaskLease lease = lease();
        ResumeAiQuotaReservation quota = quota();
        ResumeProfileAnalysisRequestedEvent prepared = event(
                ResumeProfileAnalysisMode.REFINE, quota, "关注工程能力");
        when(analysisStateService.previewRetry(2L, 1L, ResumeProfileAnalysisMode.REFINE))
                .thenReturn(new RetryPlan(ResumeProfileAnalysisMode.REFINE, true));
        when(admissionService.acquire(1L, 2L)).thenReturn(lease);
        when(quotaService.reserve(1L)).thenReturn(quota);
        when(analysisStateService.prepareRetry(
                2L,
                1L,
                ResumeProfileAnalysisMode.REFINE,
                ResumeProfileAnalysisMode.REFINE,
                quota,
                "关注工程能力"))
                .thenReturn(prepared);

        service.submitManualAnalysis(
                1L, 2L, new ResumeProfileAnalysisRetryRequest("REFINE", "  关注工程能力  "));

        InOrder order = inOrder(analysisStateService, admissionService, quotaService, eventPublisher);
        order.verify(analysisStateService).previewRetry(2L, 1L, ResumeProfileAnalysisMode.REFINE);
        order.verify(admissionService).acquire(1L, 2L);
        order.verify(quotaService).reserve(1L);
        order.verify(analysisStateService).prepareRetry(
                2L,
                1L,
                ResumeProfileAnalysisMode.REFINE,
                ResumeProfileAnalysisMode.REFINE,
                quota,
                "关注工程能力");
        order.verify(eventPublisher).publishEvent(prepared.withTaskLease(lease));
    }

    @Test
    void shouldRejectPreviewWithoutPermitQuotaOrStateSideEffects() {
        when(analysisStateService.previewRetry(2L, 1L, ResumeProfileAnalysisMode.REFINE))
                .thenThrow(new BusinessException(
                        ResumeErrorCode.PROFILE_ANALYSIS_REFINE_NOT_ALLOWED,
                        "当前没有可用于调整的同版本成功分析"));

        assertThatThrownBy(() -> service.submitManualAnalysis(
                1L, 2L, new ResumeProfileAnalysisRetryRequest("REFINE", "关注证据")))
                .isInstanceOf(BusinessException.class);

        verifyNoInteractions(admissionService, quotaService, eventPublisher);
        verify(analysisStateService, never()).prepareRetry(any(), any(), any(), any(), any(), any());
    }

    @Test
    void shouldSwallowOptionalInitialPermitFailureAfterFactsWereConfirmed() {
        when(admissionService.acquire(1L, 2L)).thenThrow(new BusinessException(
                ResumeErrorCode.USER_AI_CONCURRENCY_LIMIT,
                "当前用户的 AI 任务数已达上限"));

        assertThatCode(() -> service.submitOptionalInitial(
                1L, new ConfirmedResume(2L, "profile-hash", true)))
                .doesNotThrowAnyException();

        verify(analysisStateService, never()).prepareOptionalInitial(any(), any(), any());
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void shouldReleasePermitWhenOptionalInitialBecomesIneligibleUnderLock() {
        ResumeAiTaskLease lease = lease();
        when(admissionService.acquire(1L, 2L)).thenReturn(lease);
        when(analysisStateService.prepareOptionalInitial(2L, 1L, "profile-hash"))
                .thenReturn(Optional.empty());

        service.submitOptionalInitial(1L, new ConfirmedResume(2L, "profile-hash", true));

        verify(leaseRunner).releaseWithoutRun(1L, 2L, lease);
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void shouldFailRegisteredManualTaskAndReleaseResourcesWhenHandoffFails() {
        ResumeAiTaskLease lease = lease();
        ResumeAiQuotaReservation quota = quota();
        ResumeProfileAnalysisRequestedEvent prepared = event(
                ResumeProfileAnalysisMode.REGENERATE, quota, null);
        when(analysisStateService.previewRetry(2L, 1L, ResumeProfileAnalysisMode.REGENERATE))
                .thenReturn(new RetryPlan(ResumeProfileAnalysisMode.REGENERATE, true));
        when(admissionService.acquire(1L, 2L)).thenReturn(lease);
        when(quotaService.reserve(1L)).thenReturn(quota);
        when(analysisStateService.prepareRetry(
                2L,
                1L,
                ResumeProfileAnalysisMode.REGENERATE,
                ResumeProfileAnalysisMode.REGENERATE,
                quota,
                null))
                .thenReturn(prepared);
        org.mockito.Mockito.doThrow(new IllegalStateException("executor closed"))
                .when(eventPublisher).publishEvent(prepared.withTaskLease(lease));

        assertThatThrownBy(() -> service.submitManualAnalysis(
                1L, 2L, new ResumeProfileAnalysisRetryRequest("REGENERATE", null)))
                .isInstanceOf(IllegalStateException.class);

        verify(analysisStateService).fail(
                2L,
                1L,
                prepared.taskGeneration(),
                prepared.taskProfileHash(),
                "SCHEDULING_FAILED",
                "分析任务提交失败，请稍后重试");
        verify(leaseRunner).releaseWithoutRun(1L, 2L, lease);
        verify(quotaService).markFailed(1L, quota);
    }

    @Test
    void shouldRedactFeedbackFromRequestAndEventToString() {
        String secret = "只允许本次模型使用的意见";
        ResumeProfileAnalysisRetryRequest request =
                new ResumeProfileAnalysisRetryRequest("REFINE", secret);
        ResumeProfileAnalysisRequestedEvent event = event(
                ResumeProfileAnalysisMode.REFINE, quota(), secret);

        assertThat(request.toString()).doesNotContain(secret).contains("<redacted>");
        assertThat(event.toString()).doesNotContain(secret);
    }

    private void assertInvalid(ResumeProfileAnalysisRetryRequest request) {
        assertThatThrownBy(() -> service.submitManualAnalysis(1L, 2L, request))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getCode())
                        .isEqualTo(ResumeErrorCode.PROFILE_ANALYSIS_REQUEST_INVALID));
    }

    private ResumeProfileAnalysisRequestedEvent event(
            ResumeProfileAnalysisMode mode,
            ResumeAiQuotaReservation quota,
            String feedback) {
        return new ResumeProfileAnalysisRequestedEvent(
                2L,
                1L,
                3L,
                "profile-hash",
                null,
                quota,
                mode == ResumeProfileAnalysisMode.REFINE ? feedback : null,
                true);
    }

    private ResumeAiTaskLease lease() {
        return new ResumeAiTaskLease("user-permit", "resume-permit");
    }

    private ResumeAiQuotaReservation quota() {
        return new ResumeAiQuotaReservation(LocalDate.of(2026, 7, 30), "quota-token");
    }
}
