package com.interviewcoach.resume.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.interviewcoach.resume.domain.entity.Resume;
import com.interviewcoach.resume.domain.entity.ResumeParseStatus;
import com.interviewcoach.resume.domain.entity.ResumeProfile;
import com.interviewcoach.resume.domain.entity.ResumeProfileAnalysis;
import com.interviewcoach.resume.domain.entity.ResumeProfileAnalysisStatus;
import com.interviewcoach.resume.domain.model.UserProfileData;
import com.interviewcoach.resume.domain.repository.ResumeProfileAnalysisRepository;
import com.interviewcoach.resume.domain.repository.ResumeProfileDraftRepository;
import com.interviewcoach.resume.domain.repository.ResumeProfileRepository;
import com.interviewcoach.resume.domain.repository.ResumeRepository;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ResumePersistenceServiceTest {

    @Mock private ResumeRepository resumeRepository;
    @Mock private ResumeProfileRepository profileRepository;
    @Mock private ResumeProfileDraftRepository draftRepository;
    @Mock private ResumeProfileAnalysisRepository analysisRepository;
    @Mock private ResumeProfileSupport profileSupport;

    private ResumePersistenceService service;

    @BeforeEach
    void setUp() {
        service = new ResumePersistenceService(
                resumeRepository,
                profileRepository,
                draftRepository,
                analysisRepository,
                profileSupport);
    }

    @Test
    void shouldKeepUsableResultWhenConfirmedFactsHaveSameHash() {
        Resume resume = pendingConfirmation();
        ResumeProfileAnalysis analysis = retainedAnalysis("same-hash");
        analysis.setInitialModelCallStarted(true);
        UserProfileData data = UserProfileData.empty();
        when(resumeRepository.findByIdAndUserIdForUpdate(1L, 2L))
                .thenReturn(Optional.of(resume));
        when(profileSupport.confirmDraft(1L, 2L, 3L, data))
                .thenReturn(new ResumeProfileSupport.ConfirmedProfile(
                        new ResumeProfile(), data, "same-hash"));
        when(analysisRepository.findByResumeIdAndUserIdForUpdate(1L, 2L))
                .thenReturn(Optional.of(analysis));

        ResumePersistenceService.ConfirmedResume result =
                service.confirmProfile(2L, 1L, 3L, data);

        assertThat(analysis.isUsableForInterview()).isTrue();
        assertThat(result.initialEligible()).isFalse();
        verify(analysisRepository, never()).save(analysis);
    }

    @Test
    void shouldDisableButPreserveResultWhenConfirmedFactsHashChanges() {
        Resume resume = pendingConfirmation();
        ResumeProfileAnalysis analysis = retainedAnalysis("old-hash");
        analysis.setInitialModelCallStarted(true);
        String retainedData = analysis.getAnalysisData();
        UserProfileData data = UserProfileData.empty();
        when(resumeRepository.findByIdAndUserIdForUpdate(1L, 2L))
                .thenReturn(Optional.of(resume));
        when(profileSupport.confirmDraft(1L, 2L, 3L, data))
                .thenReturn(new ResumeProfileSupport.ConfirmedProfile(
                        new ResumeProfile(), data, "new-hash"));
        when(analysisRepository.findByResumeIdAndUserIdForUpdate(1L, 2L))
                .thenReturn(Optional.of(analysis));

        ResumePersistenceService.ConfirmedResume result =
                service.confirmProfile(2L, 1L, 3L, data);

        assertThat(analysis.isUsableForInterview()).isFalse();
        assertThat(analysis.getSourceProfileHash()).isEqualTo("old-hash");
        assertThat(analysis.getAnalysisData()).isEqualTo(retainedData);
        assertThat(result.initialEligible()).isFalse();
        verify(analysisRepository).save(analysis);
    }

    private Resume pendingConfirmation() {
        Resume resume = new Resume();
        resume.setId(1L);
        resume.setUserId(2L);
        resume.setParseGeneration(3L);
        resume.setParseStatus(ResumeParseStatus.PENDING_CONFIRM);
        return resume;
    }

    private ResumeProfileAnalysis retainedAnalysis(String sourceHash) {
        ResumeProfileAnalysis analysis = new ResumeProfileAnalysis();
        analysis.setResumeId(1L);
        analysis.setUserId(2L);
        analysis.setSourceProfileHash(sourceHash);
        analysis.setAnalysisData("retained-analysis");
        analysis.setStatus(ResumeProfileAnalysisStatus.SUCCEEDED);
        analysis.setUsableForInterview(true);
        return analysis;
    }
}
