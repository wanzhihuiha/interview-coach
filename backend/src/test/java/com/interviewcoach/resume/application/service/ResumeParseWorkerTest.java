package com.interviewcoach.resume.application.service;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.interviewcoach.resume.application.event.ResumeParseRequestedEvent;
import com.interviewcoach.resume.domain.agent.ResumeAnalysisAgent;
import com.interviewcoach.resume.domain.model.UserProfileData;
import com.interviewcoach.resume.infrastructure.parser.ResumeTextExtractor;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * 简历后台解析任务测试。
 */
@ExtendWith(MockitoExtension.class)
class ResumeParseWorkerTest {

    @Mock
    private ResumeParseStateService stateService;
    @Mock
    private ResumeTextExtractor textExtractor;
    @Mock
    private ResumeAnalysisAgent resumeAnalysisAgent;
    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private ResumeParseWorker worker;

    @BeforeEach
    void setUp() {
        worker = new ResumeParseWorker(
                stateService, textExtractor, resumeAnalysisAgent, redisTemplate, objectMapper);
    }

    @Test
    void shouldUseCachedProfileForNormalParse() throws Exception {
        ResumeParseRequestedEvent event = new ResumeParseRequestedEvent(1L, 2L, false);
        UserProfileData cachedProfile = UserProfileData.empty();
        cachedProfile.setSkillTags(List.of("Java"));
        when(stateService.start(1L, 2L))
                .thenReturn(new ResumeParseStateService.ParseInput("resume.txt", "TXT"));
        when(textExtractor.extractFromFile("resume.txt", "TXT")).thenReturn("Java developer");
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString())).thenReturn(objectMapper.writeValueAsString(cachedProfile));
        when(stateService.complete(1L, 2L, cachedProfile)).thenReturn(true);

        worker.parse(event);

        verify(resumeAnalysisAgent, never()).analyze(anyString());
        verify(stateService).complete(1L, 2L, cachedProfile);
    }

    @Test
    void shouldBypassOldCacheForForcedReparse() {
        ResumeParseRequestedEvent event = new ResumeParseRequestedEvent(1L, 2L, true);
        UserProfileData refreshedProfile = UserProfileData.empty();
        refreshedProfile.setSkillTags(List.of("Spring Boot"));
        when(stateService.start(1L, 2L))
                .thenReturn(new ResumeParseStateService.ParseInput("resume.txt", "TXT"));
        when(textExtractor.extractFromFile("resume.txt", "TXT")).thenReturn("Spring developer");
        when(resumeAnalysisAgent.analyze("Spring developer")).thenReturn(refreshedProfile);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(stateService.complete(1L, 2L, refreshedProfile)).thenReturn(true);

        worker.parse(event);

        verify(valueOperations, never()).get(anyString());
        verify(valueOperations).set(anyString(), anyString(), eq(Duration.ofDays(7)));
        verify(stateService).complete(1L, 2L, refreshedProfile);
    }

    @Test
    void shouldMarkTaskFailedWhenTextExtractionFails() {
        ResumeParseRequestedEvent event = new ResumeParseRequestedEvent(1L, 2L, false);
        when(stateService.start(1L, 2L))
                .thenReturn(new ResumeParseStateService.ParseInput("missing.txt", "TXT"));
        when(textExtractor.extractFromFile("missing.txt", "TXT"))
                .thenThrow(new IllegalArgumentException("file missing"));

        worker.parse(event);

        verify(stateService).fail(1L, 2L);
    }
}
