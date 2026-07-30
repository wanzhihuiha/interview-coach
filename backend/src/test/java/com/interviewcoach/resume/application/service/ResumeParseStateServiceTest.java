package com.interviewcoach.resume.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.interviewcoach.resume.domain.entity.Resume;
import com.interviewcoach.resume.domain.entity.ResumeParseStatus;
import com.interviewcoach.resume.domain.model.UserProfileData;
import com.interviewcoach.resume.domain.repository.ResumeRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ResumeParseStateServiceTest {

    @Mock
    private ResumeRepository resumeRepository;

    @Mock
    private ResumeProfileSupport profileSupport;

    private ResumeParseStateService stateService;

    @BeforeEach
    void setUp() {
        stateService = new ResumeParseStateService(resumeRepository, profileSupport);
    }

    @Test
    void shouldDiscardResultFromOldGeneration() {
        Resume resume = new Resume();
        resume.setId(1L);
        resume.setUserId(2L);
        resume.setParseStatus(ResumeParseStatus.PARSING);
        resume.setParseGeneration(3L);
        UserProfileData oldResult = UserProfileData.empty();
        oldResult.setSkillTags(List.of("Java"));
        when(resumeRepository.findByIdAndUserIdForUpdate(1L, 2L)).thenReturn(Optional.of(resume));

        boolean completed = stateService.complete(1L, 2L, 2L, oldResult);

        assertThat(completed).isFalse();
        verify(profileSupport, never()).saveDraft(1L, 2L, 2L, oldResult);
        verify(resumeRepository, never()).save(resume);
    }
}
