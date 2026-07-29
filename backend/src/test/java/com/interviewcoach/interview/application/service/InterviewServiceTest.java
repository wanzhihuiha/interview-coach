package com.interviewcoach.interview.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.interviewcoach.interview.application.dto.InterviewReportResponse;
import com.interviewcoach.interview.domain.entity.Interview;
import com.interviewcoach.interview.domain.entity.InterviewReport;
import com.interviewcoach.interview.domain.repository.InterviewReportRepository;
import com.interviewcoach.interview.domain.repository.InterviewRepository;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class InterviewServiceTest {

    @Mock
    private InterviewRepository interviewRepository;

    @Mock
    private InterviewReportRepository reportRepository;

    private InterviewService interviewService;

    @BeforeEach
    void setUp() {
        interviewService = new InterviewService(
                interviewRepository,
                null,
                reportRepository,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                new ObjectMapper());
    }

    @Test
    void shouldRebuildCachedMarkdownWithChinesePhaseLabelsAndNullableLists() {
        Long userId = 1L;
        Long interviewId = 7L;
        InterviewReport cached = new InterviewReport();
        cached.setInterviewId(interviewId);
        cached.setOverallScore(80);
        cached.setGrade("良好");
        cached.setPhases("{\"SELF_INTRO\":{\"completed\":true,\"questionCount\":1},\"ENDING\":null}");
        cached.setConclusion("整体表现良好");

        when(interviewRepository.findByIdAndUserId(interviewId, userId))
                .thenReturn(Optional.of(new Interview()));
        when(reportRepository.findByInterviewId(interviewId)).thenReturn(Optional.of(cached));

        InterviewReportResponse response = interviewService.getReport(userId, interviewId);

        assertThat(response.getPhases().get("SELF_INTRO").getPhaseLabel()).isEqualTo("自我介绍");
        assertThat(response.getPhases().get("ENDING").getPhaseLabel()).isEqualTo("结束");
        assertThat(response.getStrengths()).isEmpty();
        assertThat(response.getWeaknesses()).isEmpty();
        assertThat(response.getKeyEvents()).isEmpty();
        assertThat(response.getMdContent()).contains("| 自我介绍 | 1 | 已完成 |");
    }
}
