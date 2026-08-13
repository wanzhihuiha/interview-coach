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

/**
 * 验证简历持久化服务在事务内确认事实画像时，对草稿、正式画像和已保留辅助分析的处理边界。
 *
 * <p>仓储与画像支持组件均为 Mock；用例确认事实 hash 未变时保留可用结果，变化时只禁用而不删除旧分析正文。</p>
 */
@ExtendWith(MockitoExtension.class)
class ResumePersistenceServiceTest {

    /** 模拟按用户归属加锁读取并更新简历确认状态。 */
    @Mock private ResumeRepository resumeRepository;
    /** 模拟正式事实画像的读取与保存。 */
    @Mock private ResumeProfileRepository profileRepository;
    /** 模拟当前解析草稿的读取与删除。 */
    @Mock private ResumeProfileDraftRepository draftRepository;
    /** 模拟加锁读取并按事实 hash 更新辅助分析可用性。 */
    @Mock private ResumeProfileAnalysisRepository analysisRepository;
    /** 模拟将当前代次草稿确认成正式画像并返回事实 hash。 */
    @Mock private ResumeProfileSupport profileSupport;

    /** 使用全部仓储与画像协作者 Mock 构造的被测持久化服务。 */
    private ResumePersistenceService service;

    /** 每例重建被测服务，使仓储交互只归属于当前确认场景。 */
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
