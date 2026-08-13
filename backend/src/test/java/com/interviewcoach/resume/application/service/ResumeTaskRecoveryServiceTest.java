package com.interviewcoach.resume.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.interviewcoach.resume.application.event.ResumeAiQuotaReservation;
import com.interviewcoach.resume.domain.entity.Resume;
import com.interviewcoach.resume.domain.entity.ResumeParseStatus;
import com.interviewcoach.resume.domain.entity.ResumeProfileAnalysis;
import com.interviewcoach.resume.domain.entity.ResumeProfileAnalysisStatus;
import com.interviewcoach.resume.domain.repository.ResumeProfileAnalysisRepository;
import com.interviewcoach.resume.domain.repository.ResumeRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 验证当前进程启动恢复服务对中断解析与辅助分析任务的终态写回及额度凭据清理边界。
 *
 * <p>仓储均为 Mock；本类不验证部署是否单实例，只固定扫描结果、批量失败数量以及仅清除匹配任务凭据的行为。</p>
 */
@ExtendWith(MockitoExtension.class)
class ResumeTaskRecoveryServiceTest {

    /** 模拟扫描中断的事实解析任务、批量标记失败及清理其额度元数据。 */
    @Mock
    private ResumeRepository resumeRepository;

    /** 模拟扫描中断的辅助分析任务、批量标记失败及清理其额度元数据。 */
    @Mock
    private ResumeProfileAnalysisRepository analysisRepository;

    @Test
    void shouldMarkInterruptedTasksAndReturnPersistedQuotaContexts() {
        Resume resume = new Resume();
        resume.setId(1L);
        resume.setUserId(2L);
        resume.setParseGeneration(3L);
        resume.setParseStatus(ResumeParseStatus.PENDING);
        resume.setParseQuotaDate(LocalDate.of(2026, 7, 30));
        resume.setParseQuotaToken("parse-token");
        ResumeProfileAnalysis analysis = new ResumeProfileAnalysis();
        analysis.setResumeId(4L);
        analysis.setUserId(2L);
        analysis.setTaskGeneration(5L);
        analysis.setTaskProfileHash("profile-hash");
        analysis.setStatus(ResumeProfileAnalysisStatus.RUNNING);
        analysis.setTaskQuotaDate(LocalDate.of(2026, 7, 30));
        analysis.setTaskQuotaToken("analysis-token");
        when(resumeRepository.findByParseStatusInAndParseQuotaDateIsNotNullAndParseQuotaTokenIsNotNull(
                eq(List.of(
                        ResumeParseStatus.PENDING,
                        ResumeParseStatus.PARSING,
                        ResumeParseStatus.PARSE_FAILED))))
                .thenReturn(List.of(resume));
        when(analysisRepository.findByStatusInAndTaskQuotaDateIsNotNullAndTaskQuotaTokenIsNotNull(
                eq(List.of(
                        ResumeProfileAnalysisStatus.PENDING,
                        ResumeProfileAnalysisStatus.RUNNING,
                        ResumeProfileAnalysisStatus.FAILED))))
                .thenReturn(List.of(analysis));
        when(resumeRepository.markInterruptedParsesAsFailed(
                eq(List.of(ResumeParseStatus.PENDING, ResumeParseStatus.PARSING)),
                eq(ResumeParseStatus.PARSE_FAILED),
                eq("APPLICATION_RESTARTED"),
                eq("应用重启导致解析中断，请手动重试"),
                any(LocalDateTime.class))).thenReturn(2);
        when(analysisRepository.markInterruptedAsFailed(
                eq(List.of(ResumeProfileAnalysisStatus.PENDING, ResumeProfileAnalysisStatus.RUNNING)),
                eq(ResumeProfileAnalysisStatus.FAILED),
                eq("APPLICATION_RESTARTED"),
                eq("应用重启导致分析中断，请手动重试"),
                any(LocalDateTime.class))).thenReturn(1);
        ResumeTaskRecoveryService service =
                new ResumeTaskRecoveryService(resumeRepository, analysisRepository);

        ResumeTaskRecoveryService.RecoveryResult result = service.failInterruptedTasks();

        assertThat(result.parseCount()).isEqualTo(2);
        assertThat(result.analysisCount()).isEqualTo(1);
        assertThat(result.parseQuotaRecoveries()).singleElement().satisfies(recovery -> {
            assertThat(recovery.resumeId()).isEqualTo(1L);
            assertThat(recovery.generation()).isEqualTo(3L);
            assertThat(recovery.reservation().quotaToken()).isEqualTo("parse-token");
        });
        assertThat(result.analysisQuotaRecoveries()).singleElement().satisfies(recovery -> {
            assertThat(recovery.resumeId()).isEqualTo(4L);
            assertThat(recovery.taskGeneration()).isEqualTo(5L);
            assertThat(recovery.taskProfileHash()).isEqualTo("profile-hash");
            assertThat(recovery.reservation().quotaToken()).isEqualTo("analysis-token");
        });
    }

    @Test
    void shouldClearRecoveredMetadataWithoutResettingInitialQualification() {
        Resume resume = new Resume();
        resume.setId(1L);
        resume.setUserId(2L);
        resume.setParseGeneration(3L);
        resume.setParseQuotaDate(LocalDate.of(2026, 7, 30));
        resume.setParseQuotaToken("parse-token");
        ResumeProfileAnalysis analysis = new ResumeProfileAnalysis();
        analysis.setResumeId(4L);
        analysis.setUserId(2L);
        analysis.setTaskGeneration(5L);
        analysis.setTaskProfileHash("profile-hash");
        analysis.setTaskQuotaDate(LocalDate.of(2026, 7, 30));
        analysis.setTaskQuotaToken("analysis-token");
        analysis.setInitialModelCallStarted(true);
        ResumeAiQuotaReservation parseReservation = new ResumeAiQuotaReservation(
                LocalDate.of(2026, 7, 30), "parse-token");
        ResumeAiQuotaReservation analysisReservation = new ResumeAiQuotaReservation(
                LocalDate.of(2026, 7, 30), "analysis-token");
        ResumeTaskRecoveryService service =
                new ResumeTaskRecoveryService(resumeRepository, analysisRepository);
        ResumeTaskRecoveryService.ParseQuotaRecovery parseRecovery =
                new ResumeTaskRecoveryService.ParseQuotaRecovery(1L, 2L, 3L, parseReservation);
        ResumeTaskRecoveryService.AnalysisQuotaRecovery analysisRecovery =
                new ResumeTaskRecoveryService.AnalysisQuotaRecovery(
                        4L, 2L, 5L, "profile-hash", analysisReservation);
        when(resumeRepository.findByIdAndUserIdForUpdate(1L, 2L))
                .thenReturn(Optional.of(resume));
        when(analysisRepository.findByResumeIdAndUserIdForUpdate(4L, 2L))
                .thenReturn(Optional.of(analysis));

        service.clearRecoveredParseQuota(parseRecovery);
        service.clearRecoveredAnalysisQuota(analysisRecovery);

        assertThat(resume.getParseQuotaDate()).isNull();
        assertThat(resume.getParseQuotaToken()).isNull();
        assertThat(analysis.getTaskQuotaDate()).isNull();
        assertThat(analysis.getTaskQuotaToken()).isNull();
        assertThat(analysis.isInitialModelCallStarted()).isTrue();
        verify(resumeRepository).save(resume);
        verify(analysisRepository).save(analysis);
    }
}
