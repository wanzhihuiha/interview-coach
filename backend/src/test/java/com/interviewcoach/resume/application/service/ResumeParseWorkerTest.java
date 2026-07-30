package com.interviewcoach.resume.application.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.interviewcoach.resume.application.event.ResumeAiQuotaReservation;
import com.interviewcoach.resume.application.event.ResumeAiTaskLease;
import com.interviewcoach.resume.application.event.ResumeParseRequestedEvent;
import com.interviewcoach.resume.domain.agent.ResumeAnalysisAgent;
import com.interviewcoach.resume.domain.model.UserProfileData;
import com.interviewcoach.resume.infrastructure.cache.ResumeParseCacheProperties;
import com.interviewcoach.resume.infrastructure.parser.ResumeTextExtractor;
import com.interviewcoach.resume.infrastructure.redis.ResumeAiQuotaService;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

@ExtendWith(MockitoExtension.class)
class ResumeParseWorkerTest {

    @Mock private ResumeParseStateService stateService;
    @Mock private ResumeTextExtractor textExtractor;
    @Mock private ResumeAnalysisAgent resumeAnalysisAgent;
    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOperations;
    @Mock private ResumeAiQuotaService quotaService;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private ResumeParseCacheProperties cacheProperties;
    private ResumeParseWorker worker;

    @BeforeEach
    void setUp() {
        cacheProperties = new ResumeParseCacheProperties();
        cacheProperties.setEnabled(true);
        worker = new ResumeParseWorker(
                stateService,
                textExtractor,
                resumeAnalysisAgent,
                new ResumeProfileNormalizer(),
                redisTemplate,
                objectMapper,
                cacheProperties,
                quotaService);
    }

    @Test
    void shouldUseUserScopedCachedProfileForFreeParse() throws Exception {
        ResumeParseRequestedEvent event = event(7L, false, null);
        UserProfileData cachedProfile = validProfile("Java");
        when(stateService.start(1L, 2L, 7L))
                .thenReturn(input("resume.txt", "TXT", null));
        when(textExtractor.extractFromFile("resume.txt", "TXT")).thenReturn("Java developer");
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString())).thenReturn(objectMapper.writeValueAsString(cachedProfile));
        when(stateService.complete(1L, 2L, 7L, cachedProfile)).thenReturn(true);

        worker.parse(event);

        verify(valueOperations).get(org.mockito.ArgumentMatchers.startsWith("resume:parse:u2:"));
        verify(resumeAnalysisAgent, never()).analyze(anyString(), any());
        verify(stateService).complete(1L, 2L, 7L, cachedProfile);
    }

    @Test
    void shouldBypassCacheForForcedFreeReparse() {
        ResumeParseRequestedEvent event = event(8L, true, null);
        UserProfileData refreshed = validProfile("Spring Boot");
        when(stateService.start(1L, 2L, 8L))
                .thenReturn(input("resume.txt", "TXT", null));
        when(textExtractor.extractFromFile("resume.txt", "TXT")).thenReturn("Spring developer");
        when(resumeAnalysisAgent.analyze(eq("Spring developer"), any())).thenReturn(refreshed);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(stateService.complete(1L, 2L, 8L, refreshed)).thenReturn(true);

        worker.parse(event);

        verify(valueOperations, never()).get(anyString());
        verify(valueOperations).set(anyString(), anyString(), eq(cacheProperties.getTtl()));
        verify(stateService).complete(1L, 2L, 8L, refreshed);
    }

    @Test
    void shouldCallModelWhenBadCacheCannotBeDeleted() {
        ResumeParseRequestedEvent event = event(9L, false, null);
        UserProfileData refreshed = validProfile("Redis");
        when(stateService.start(1L, 2L, 9L))
                .thenReturn(input("resume.txt", "TXT", null));
        when(textExtractor.extractFromFile("resume.txt", "TXT")).thenReturn("Redis developer");
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString())).thenReturn("not-json");
        when(redisTemplate.delete(anyString())).thenThrow(new RuntimeException("redis unavailable"));
        when(resumeAnalysisAgent.analyze(eq("Redis developer"), any())).thenReturn(refreshed);

        worker.parse(event);

        verify(resumeAnalysisAgent).analyze(eq("Redis developer"), any());
        verify(stateService).complete(1L, 2L, 9L, refreshed);
    }

    @Test
    void shouldReleaseQuotaWithoutAttemptWhenExtractionFails() {
        ResumeAiQuotaReservation quota = quota();
        ResumeParseRequestedEvent event = event(10L, true, quota);
        when(stateService.start(1L, 2L, 10L))
                .thenReturn(input("missing.txt", "TXT", quota));
        when(textExtractor.extractFromFile("missing.txt", "TXT"))
                .thenThrow(new IllegalArgumentException("file missing"));

        worker.parse(event);

        verify(quotaService, never()).markAttemptStarted(2L, quota);
        verify(quotaService).markFailed(2L, quota);
        verify(stateService).fail(1L, 2L, 10L,
                "TEXT_EXTRACTION_FAILED", "简历文本提取失败");
    }

    @Test
    void shouldCountOneAttemptAndSuccessForManualReparse() {
        ResumeAiQuotaReservation quota = quota();
        ResumeParseRequestedEvent event = event(11L, true, quota);
        UserProfileData profile = validProfile("Java");
        when(stateService.start(1L, 2L, 11L))
                .thenReturn(input("resume.txt", "TXT", quota));
        when(textExtractor.extractFromFile("resume.txt", "TXT")).thenReturn("Java developer");
        doAnswer(invocation -> {
            ((Runnable) invocation.getArgument(1)).run();
            return profile;
        }).when(resumeAnalysisAgent).analyze(eq("Java developer"), any());
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(quotaService.markAttemptStarted(2L, quota)).thenReturn(true);
        when(stateService.complete(1L, 2L, 11L, profile)).thenReturn(true);
        when(quotaService.markSucceeded(2L, quota)).thenReturn(true);

        worker.parse(event);

        InOrder order = inOrder(quotaService, stateService);
        order.verify(quotaService).markAttemptStarted(2L, quota);
        order.verify(stateService).complete(1L, 2L, 11L, profile);
        order.verify(quotaService).markSucceeded(2L, quota);
        verify(quotaService, never()).markFailed(2L, quota);
    }

    @Test
    void shouldKeepAttemptButReleaseSuccessWhenModelFailsAfterCallback() {
        ResumeAiQuotaReservation quota = quota();
        ResumeParseRequestedEvent event = event(12L, true, quota);
        when(stateService.start(1L, 2L, 12L))
                .thenReturn(input("resume.txt", "TXT", quota));
        when(textExtractor.extractFromFile("resume.txt", "TXT")).thenReturn("Java developer");
        when(quotaService.markAttemptStarted(2L, quota)).thenReturn(true);
        doAnswer(invocation -> {
            ((Runnable) invocation.getArgument(1)).run();
            throw new IllegalStateException("model failed");
        }).when(resumeAnalysisAgent).analyze(eq("Java developer"), any());

        worker.parse(event);

        verify(quotaService).markAttemptStarted(2L, quota);
        verify(quotaService).markFailed(2L, quota);
        verify(quotaService, never()).markSucceeded(2L, quota);
    }

    @Test
    void shouldKeepSuccessReservationWhenRedisFailsAfterDatabaseSuccess() {
        ResumeAiQuotaReservation quota = quota();
        ResumeParseRequestedEvent event = event(13L, true, quota);
        UserProfileData profile = validProfile("Java");
        when(stateService.start(1L, 2L, 13L))
                .thenReturn(input("resume.txt", "TXT", quota));
        when(textExtractor.extractFromFile("resume.txt", "TXT")).thenReturn("Java developer");
        doAnswer(invocation -> {
            ((Runnable) invocation.getArgument(1)).run();
            return profile;
        }).when(resumeAnalysisAgent).analyze(eq("Java developer"), any());
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(quotaService.markAttemptStarted(2L, quota)).thenReturn(true);
        when(stateService.complete(1L, 2L, 13L, profile)).thenReturn(true);
        doThrow(new IllegalStateException("redis unavailable"))
                .when(quotaService).markSucceeded(2L, quota);

        worker.parse(event);

        verify(quotaService, never()).markFailed(2L, quota);
        verify(stateService, never()).fail(any(), any(), any(), any(), any());
    }

    @Test
    void shouldReleaseEventReservationWhenTaskIsAlreadyStale() {
        ResumeAiQuotaReservation quota = quota();
        ResumeParseRequestedEvent event = event(14L, true, quota);
        when(stateService.start(1L, 2L, 14L)).thenReturn(null);

        worker.parse(event);

        verify(quotaService).markFailed(2L, quota);
        verify(resumeAnalysisAgent, never()).analyze(anyString(), any());
    }

    @Test
    void shouldNotPersistResultAfterLeaseIsLost() {
        ResumeParseRequestedEvent event = event(15L, true, null);
        event.taskLease().markLost();
        UserProfileData profile = validProfile("Java");
        when(stateService.start(1L, 2L, 15L))
                .thenReturn(input("resume.txt", "TXT", null));
        when(textExtractor.extractFromFile("resume.txt", "TXT")).thenReturn("Java developer");
        when(resumeAnalysisAgent.analyze(eq("Java developer"), any())).thenReturn(profile);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        worker.parse(event);

        verify(stateService, never()).complete(1L, 2L, 15L, profile);
        verify(stateService).fail(
                1L, 2L, 15L, "RESULT_PERSISTENCE_FAILED", "简历解析失败，请重试");
    }

    private UserProfileData validProfile(String skill) {
        UserProfileData data = UserProfileData.empty();
        data.setSkillTags(List.of(skill));
        return data;
    }

    private ResumeParseStateService.ParseInput input(
            String path, String type, ResumeAiQuotaReservation quota) {
        return new ResumeParseStateService.ParseInput(path, type, quota);
    }

    private ResumeParseRequestedEvent event(
            Long generation, boolean forceRefresh, ResumeAiQuotaReservation quota) {
        return new ResumeParseRequestedEvent(
                1L,
                2L,
                generation,
                forceRefresh,
                new ResumeAiTaskLease("user-permit", "resume-permit"),
                quota);
    }

    private ResumeAiQuotaReservation quota() {
        return new ResumeAiQuotaReservation(LocalDate.of(2026, 7, 30), "quota-token");
    }
}
