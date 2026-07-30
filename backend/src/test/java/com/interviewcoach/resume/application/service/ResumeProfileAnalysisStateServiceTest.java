package com.interviewcoach.resume.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.interviewcoach.common.exception.BusinessException;
import com.interviewcoach.resume.application.event.ResumeAiQuotaReservation;
import com.interviewcoach.resume.application.event.ResumeProfileAnalysisRequestedEvent;
import com.interviewcoach.resume.domain.entity.Resume;
import com.interviewcoach.resume.domain.entity.ResumeParseStatus;
import com.interviewcoach.resume.domain.entity.ResumeProfile;
import com.interviewcoach.resume.domain.entity.ResumeProfileAnalysis;
import com.interviewcoach.resume.domain.entity.ResumeProfileAnalysisMode;
import com.interviewcoach.resume.domain.entity.ResumeProfileAnalysisStatus;
import com.interviewcoach.resume.domain.model.ResumeProfileAnalysisData;
import com.interviewcoach.resume.domain.model.UserProfileData;
import com.interviewcoach.resume.domain.repository.ResumeProfileAnalysisRepository;
import com.interviewcoach.resume.domain.repository.ResumeProfileRepository;
import com.interviewcoach.resume.domain.repository.ResumeRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ResumeProfileAnalysisStateServiceTest {

    @Mock private ResumeProfileAnalysisRepository analysisRepository;
    @Mock private ResumeProfileRepository profileRepository;
    @Mock private ResumeRepository resumeRepository;
    @Mock private ResumeProfileSupport profileSupport;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private ResumeProfileAnalysisStateService stateService;

    @BeforeEach
    void setUp() {
        stateService = new ResumeProfileAnalysisStateService(
                analysisRepository,
                profileRepository,
                resumeRepository,
                profileSupport,
                objectMapper);
    }

    @Test
    void shouldPrepareOptionalInitialWithoutInventingSuccessfulResult() {
        when(resumeRepository.findByIdAndUserIdForUpdate(1L, 2L))
                .thenReturn(Optional.of(confirmedResume()));
        when(profileRepository.findByResumeIdAndUserIdForUpdate(1L, 2L))
                .thenReturn(Optional.of(profile("current-hash")));
        when(analysisRepository.findByResumeIdAndUserIdForUpdate(1L, 2L))
                .thenReturn(Optional.empty());
        when(analysisRepository.save(org.mockito.ArgumentMatchers.any(ResumeProfileAnalysis.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ResumeProfileAnalysisRequestedEvent event = stateService.prepareOptionalInitial(
                1L, 2L, "current-hash").orElseThrow();

        assertThat(event.taskGeneration()).isEqualTo(1L);
        assertThat(event.taskProfileHash()).isEqualTo("current-hash");
        assertThat(event.quotaReservation()).isNull();
        assertThat(event.feedback()).isNull();
        assertThat(event.requiredHandoff()).isFalse();
    }

    @Test
    void shouldMapFirstPublicRegenerateToFreeInitial() {
        when(resumeRepository.findByIdAndUserId(1L, 2L))
                .thenReturn(Optional.of(confirmedResume()));
        when(profileRepository.findByResumeIdAndUserId(1L, 2L))
                .thenReturn(Optional.of(profile("current-hash")));
        when(analysisRepository.findByResumeIdAndUserId(1L, 2L))
                .thenReturn(Optional.empty());

        ResumeProfileAnalysisStateService.RetryPlan plan = stateService.previewRetry(
                1L, 2L, ResumeProfileAnalysisMode.REGENERATE);

        assertThat(plan.taskMode()).isEqualTo(ResumeProfileAnalysisMode.INITIAL);
        assertThat(plan.quotaRequired()).isFalse();
    }

    @Test
    void shouldCarryQuotaMetadataAndPreserveSuccessfulResultForRegenerate() {
        ResumeProfileAnalysis analysis = retainedAnalysis("current-hash");
        analysis.setStatus(ResumeProfileAnalysisStatus.FAILED);
        analysis.setTaskGeneration(4L);
        analysis.setInitialModelCallStarted(true);
        LocalDateTime generatedAt = LocalDateTime.of(2026, 7, 30, 9, 0);
        analysis.setGeneratedAt(generatedAt);
        analysis.setSchemaVersion(7);
        analysis.setPromptVersion("analysis-v0");
        analysis.setModelName("old-model");
        ResumeAiQuotaReservation quota = quota();
        when(resumeRepository.findByIdAndUserIdForUpdate(1L, 2L))
                .thenReturn(Optional.of(confirmedResume()));
        when(profileRepository.findByResumeIdAndUserIdForUpdate(1L, 2L))
                .thenReturn(Optional.of(profile("current-hash")));
        when(analysisRepository.findByResumeIdAndUserIdForUpdate(1L, 2L))
                .thenReturn(Optional.of(analysis));
        when(analysisRepository.save(analysis)).thenReturn(analysis);

        ResumeProfileAnalysisRequestedEvent event = stateService.prepareRetry(
                1L,
                2L,
                ResumeProfileAnalysisMode.REGENERATE,
                ResumeProfileAnalysisMode.REGENERATE,
                quota,
                "ignored feedback");

        assertThat(analysis.getAnalysisData()).isEqualTo(emptyAnalysisJson());
        assertThat(analysis.getSourceProfileHash()).isEqualTo("current-hash");
        assertThat(analysis.getGeneratedAt()).isEqualTo(generatedAt);
        assertThat(analysis.getSchemaVersion()).isEqualTo(7);
        assertThat(analysis.getPromptVersion()).isEqualTo("analysis-v0");
        assertThat(analysis.getModelName()).isEqualTo("old-model");
        assertThat(analysis.getTaskGeneration()).isEqualTo(5L);
        assertThat(analysis.getTaskMode()).isEqualTo(ResumeProfileAnalysisMode.REGENERATE);
        assertThat(analysis.isUsableForInterview()).isFalse();
        assertThat(event.quotaReservation()).isEqualTo(quota);
        assertThat(event.feedback()).isNull();
        assertThat(event.requiredHandoff()).isTrue();
    }

    @Test
    void shouldAllowRefineOnlyForParsableSameHashRetainedResult() {
        ResumeProfileAnalysis analysis = retainedAnalysis("current-hash");
        analysis.setStatus(ResumeProfileAnalysisStatus.FAILED);
        when(resumeRepository.findByIdAndUserId(1L, 2L))
                .thenReturn(Optional.of(confirmedResume()));
        when(profileRepository.findByResumeIdAndUserId(1L, 2L))
                .thenReturn(Optional.of(profile("current-hash")));
        when(analysisRepository.findByResumeIdAndUserId(1L, 2L))
                .thenReturn(Optional.of(analysis));

        ResumeProfileAnalysisStateService.RetryPlan plan = stateService.previewRetry(
                1L, 2L, ResumeProfileAnalysisMode.REFINE);

        assertThat(plan.taskMode()).isEqualTo(ResumeProfileAnalysisMode.REFINE);
        assertThat(plan.quotaRequired()).isTrue();
    }

    @Test
    void shouldRejectRefineForMismatchedOrCorruptRetainedResult() {
        ResumeProfileAnalysis analysis = retainedAnalysis("old-hash");
        analysis.setStatus(ResumeProfileAnalysisStatus.FAILED);
        when(resumeRepository.findByIdAndUserId(1L, 2L))
                .thenReturn(Optional.of(confirmedResume()));
        when(profileRepository.findByResumeIdAndUserId(1L, 2L))
                .thenReturn(Optional.of(profile("current-hash")));
        when(analysisRepository.findByResumeIdAndUserId(1L, 2L))
                .thenReturn(Optional.of(analysis));

        assertRefineNotAllowed(() -> stateService.previewRetry(
                1L, 2L, ResumeProfileAnalysisMode.REFINE));

        analysis.setSourceProfileHash("current-hash");
        analysis.setAnalysisData("not-json");
        assertRefineNotAllowed(() -> stateService.previewRetry(
                1L, 2L, ResumeProfileAnalysisMode.REFINE));
    }

    @Test
    void shouldRejectRetryWhileSameResumeHasRunningAiTask() {
        Resume resume = confirmedResume();
        resume.setParseStatus(ResumeParseStatus.PARSING);
        when(resumeRepository.findByIdAndUserId(1L, 2L)).thenReturn(Optional.of(resume));
        when(profileRepository.findByResumeIdAndUserId(1L, 2L))
                .thenReturn(Optional.of(profile("current-hash")));
        when(analysisRepository.findByResumeIdAndUserId(1L, 2L))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> stateService.previewRetry(
                1L, 2L, ResumeProfileAnalysisMode.REGENERATE))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getCode())
                        .isEqualTo(ResumeErrorCode.RESUME_AI_CONCURRENCY_LIMIT));
    }

    @Test
    void shouldReturnRetainedAnalysisAndPersistedQuotaWhenStartingRefine() {
        ResumeProfileAnalysis analysis = retainedAnalysis("current-hash");
        analysis.setStatus(ResumeProfileAnalysisStatus.PENDING);
        analysis.setTaskProfileHash("current-hash");
        analysis.setTaskGeneration(3L);
        analysis.setTaskMode(ResumeProfileAnalysisMode.REFINE);
        analysis.setTaskQuotaDate(quota().quotaDate());
        analysis.setTaskQuotaToken(quota().quotaToken());
        UserProfileData profileData = UserProfileData.empty();
        ResumeProfile profile = profile("current-hash");
        profile.setProfileData("profile-json");
        when(resumeRepository.findByIdAndUserIdForUpdate(1L, 2L))
                .thenReturn(Optional.of(confirmedResume()));
        when(profileRepository.findByResumeIdAndUserIdForUpdate(1L, 2L))
                .thenReturn(Optional.of(profile));
        when(analysisRepository.findByResumeIdAndUserIdForUpdate(1L, 2L))
                .thenReturn(Optional.of(analysis));
        when(analysisRepository.save(analysis)).thenReturn(analysis);
        when(profileSupport.fromJson(1L, "profile-json")).thenReturn(profileData);

        ResumeProfileAnalysisStateService.AnalysisInput input = stateService.start(
                1L, 2L, 3L, "current-hash");

        assertThat(input.profileData()).isSameAs(profileData);
        assertThat(input.previousAnalysis()).isNotNull();
        assertThat(input.taskMode()).isEqualTo(ResumeProfileAnalysisMode.REFINE);
        assertThat(input.quotaReservation()).isEqualTo(quota());
        assertThat(analysis.getStatus()).isEqualTo(ResumeProfileAnalysisStatus.RUNNING);
    }

    @Test
    void shouldDiscardAnalysisWhenConfirmedProfileChanged() {
        ResumeProfileAnalysis analysis = runningAnalysis(3L, "old-hash");
        when(profileRepository.findByResumeIdAndUserIdForUpdate(1L, 2L))
                .thenReturn(Optional.of(profile("new-hash")));
        when(analysisRepository.findByResumeIdAndUserIdForUpdate(1L, 2L))
                .thenReturn(Optional.of(analysis));

        boolean completed = stateService.complete(
                1L, 2L, 3L, "old-hash", new ResumeProfileAnalysisData());

        assertThat(completed).isFalse();
        verify(analysisRepository, never()).save(analysis);
    }

    @Test
    void shouldDiscardLateGenerationEvenWhenTaskHashIsUnchanged() {
        ResumeProfileAnalysis analysis = runningAnalysis(8L, "same-hash");
        when(profileRepository.findByResumeIdAndUserIdForUpdate(1L, 2L))
                .thenReturn(Optional.of(profile("same-hash")));
        when(analysisRepository.findByResumeIdAndUserIdForUpdate(1L, 2L))
                .thenReturn(Optional.of(analysis));

        boolean completed = stateService.complete(
                1L, 2L, 7L, "same-hash", new ResumeProfileAnalysisData());

        assertThat(completed).isFalse();
        verify(analysisRepository, never()).save(analysis);
    }

    @Test
    void shouldWriteSuccessfulResultOnlyForCurrentTaskAndProfile() {
        ResumeProfileAnalysis analysis = runningAnalysis(8L, "current-hash");
        analysis.setTaskQuotaDate(quota().quotaDate());
        analysis.setTaskQuotaToken(quota().quotaToken());
        when(profileRepository.findByResumeIdAndUserIdForUpdate(1L, 2L))
                .thenReturn(Optional.of(profile("current-hash")));
        when(analysisRepository.findByResumeIdAndUserIdForUpdate(1L, 2L))
                .thenReturn(Optional.of(analysis));
        when(analysisRepository.save(analysis)).thenReturn(analysis);

        boolean completed = stateService.complete(
                1L, 2L, 8L, "current-hash", new ResumeProfileAnalysisData());

        assertThat(completed).isTrue();
        assertThat(analysis.getSourceProfileHash()).isEqualTo("current-hash");
        assertThat(analysis.getAnalysisData()).isNotBlank();
        assertThat(analysis.getStatus()).isEqualTo(ResumeProfileAnalysisStatus.SUCCEEDED);
        assertThat(analysis.isUsableForInterview()).isTrue();
        assertThat(analysis.getGeneratedAt()).isNotNull();
        assertThat(analysis.getTaskQuotaDate()).isNull();
        assertThat(analysis.getTaskQuotaToken()).isNull();
    }

    @Test
    void shouldMarkInitialModelCallStartedIdempotently() {
        ResumeProfileAnalysis analysis = runningAnalysis(1L, "current-hash");
        analysis.setTaskMode(ResumeProfileAnalysisMode.INITIAL);
        when(profileRepository.findByResumeIdAndUserIdForUpdate(1L, 2L))
                .thenReturn(Optional.of(profile("current-hash")));
        when(analysisRepository.findByResumeIdAndUserIdForUpdate(1L, 2L))
                .thenReturn(Optional.of(analysis));
        when(analysisRepository.save(analysis)).thenReturn(analysis);

        assertThat(stateService.markInitialModelCallStarted(1L, 2L, 1L, "current-hash"))
                .isTrue();
        assertThat(stateService.markInitialModelCallStarted(1L, 2L, 1L, "current-hash"))
                .isTrue();

        assertThat(analysis.isInitialModelCallStarted()).isTrue();
        verify(analysisRepository, times(1)).save(analysis);
    }

    @Test
    void shouldFailCurrentTaskWithoutErasingRetainedResultOrRestoringFreeQualification() {
        ResumeProfileAnalysis analysis = runningAnalysis(2L, "new-hash");
        analysis.setSourceProfileHash("old-hash");
        analysis.setAnalysisData(emptyAnalysisJson());
        analysis.setInitialModelCallStarted(true);
        analysis.setTaskQuotaDate(quota().quotaDate());
        analysis.setTaskQuotaToken(quota().quotaToken());
        when(analysisRepository.findByResumeIdAndUserIdForUpdate(1L, 2L))
                .thenReturn(Optional.of(analysis));
        when(analysisRepository.save(analysis)).thenReturn(analysis);

        stateService.fail(1L, 2L, 2L, "new-hash", "FAILED", "analysis failed");

        assertThat(analysis.getStatus()).isEqualTo(ResumeProfileAnalysisStatus.FAILED);
        assertThat(analysis.getSourceProfileHash()).isEqualTo("old-hash");
        assertThat(analysis.getAnalysisData()).isEqualTo(emptyAnalysisJson());
        assertThat(analysis.isInitialModelCallStarted()).isTrue();
        assertThat(analysis.isUsableForInterview()).isFalse();
        assertThat(analysis.getTaskQuotaDate()).isNull();
        assertThat(analysis.getTaskQuotaToken()).isNull();
    }

    @Test
    void shouldExposeMismatchedRetainedResultButDisableInterviewAndRefine() {
        ResumeProfileAnalysis analysis = retainedAnalysis("old-hash");
        when(resumeRepository.findByIdAndUserId(1L, 2L))
                .thenReturn(Optional.of(confirmedResume()));
        when(profileRepository.findByResumeIdAndUserId(1L, 2L))
                .thenReturn(Optional.of(profile("current-hash")));
        when(analysisRepository.findByResumeIdAndUserId(1L, 2L))
                .thenReturn(Optional.of(analysis));

        ResumeProfileAnalysisStateService.AnalysisView view =
                stateService.loadCurrent(1L, 2L).orElseThrow();

        assertThat(view.data()).isNotNull();
        assertThat(view.resultMatchesCurrentProfile()).isFalse();
        assertThat(view.usableForInterview()).isFalse();
        assertThat(view.refineAllowed()).isFalse();
    }

    @Test
    void shouldAllowMatchingResultButDisableRefineWhileFactParsingRuns() {
        ResumeProfileAnalysis analysis = retainedAnalysis("current-hash");
        Resume parsing = confirmedResume();
        parsing.setParseStatus(ResumeParseStatus.PARSING);
        when(resumeRepository.findByIdAndUserId(1L, 2L)).thenReturn(Optional.of(parsing));
        when(profileRepository.findByResumeIdAndUserId(1L, 2L))
                .thenReturn(Optional.of(profile("current-hash")));
        when(analysisRepository.findByResumeIdAndUserId(1L, 2L))
                .thenReturn(Optional.of(analysis));

        ResumeProfileAnalysisStateService.AnalysisView view =
                stateService.loadCurrent(1L, 2L).orElseThrow();

        assertThat(view.usableForInterview()).isTrue();
        assertThat(view.refineAllowed()).isFalse();
    }

    @Test
    void shouldIgnoreCorruptOptionalAnalysisData() {
        ResumeProfileAnalysis analysis = retainedAnalysis("current-hash");
        analysis.setStatus(ResumeProfileAnalysisStatus.SUCCEEDED);
        analysis.setAnalysisData("not-json");
        when(profileRepository.findByResumeIdAndUserId(1L, 2L))
                .thenReturn(Optional.of(profile("current-hash")));
        when(analysisRepository.findByResumeIdAndUserId(1L, 2L))
                .thenReturn(Optional.of(analysis));

        ResumeProfileAnalysisStateService.AnalysisView view =
                stateService.loadCurrent(1L, 2L).orElseThrow();

        assertThat(view.data()).isNull();
        assertThat(view.effectiveStatus()).isEqualTo(ResumeProfileAnalysisStatus.FAILED);
        assertThat(view.effectiveErrorMessage()).contains("手动重试");
        assertThat(view.usableForInterview()).isFalse();
        assertThat(view.refineAllowed()).isFalse();
    }

    private void assertRefineNotAllowed(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action)
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getCode())
                        .isEqualTo(ResumeErrorCode.PROFILE_ANALYSIS_REFINE_NOT_ALLOWED));
    }

    private Resume confirmedResume() {
        Resume resume = new Resume();
        resume.setId(1L);
        resume.setUserId(2L);
        resume.setParseStatus(ResumeParseStatus.CONFIRMED);
        return resume;
    }

    private ResumeProfile profile(String profileHash) {
        ResumeProfile profile = new ResumeProfile();
        profile.setProfileHash(profileHash);
        return profile;
    }

    private ResumeProfileAnalysis retainedAnalysis(String sourceProfileHash) {
        ResumeProfileAnalysis analysis = new ResumeProfileAnalysis();
        analysis.setResumeId(1L);
        analysis.setUserId(2L);
        analysis.setSourceProfileHash(sourceProfileHash);
        analysis.setAnalysisData(emptyAnalysisJson());
        analysis.setStatus(ResumeProfileAnalysisStatus.SUCCEEDED);
        analysis.setUsableForInterview(true);
        return analysis;
    }

    private ResumeProfileAnalysis runningAnalysis(Long generation, String taskProfileHash) {
        ResumeProfileAnalysis analysis = new ResumeProfileAnalysis();
        analysis.setResumeId(1L);
        analysis.setUserId(2L);
        analysis.setTaskGeneration(generation);
        analysis.setTaskProfileHash(taskProfileHash);
        analysis.setStatus(ResumeProfileAnalysisStatus.RUNNING);
        return analysis;
    }

    private ResumeAiQuotaReservation quota() {
        return new ResumeAiQuotaReservation(LocalDate.of(2026, 7, 30), "quota-token");
    }

    private String emptyAnalysisJson() {
        return "{\"strengths\":[],\"verificationPoints\":[],\"skillAssessments\":[]}";
    }
}
