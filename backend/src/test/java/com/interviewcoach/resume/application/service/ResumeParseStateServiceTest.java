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

/**
 * 验证简历事实解析状态服务对任务代次、当前任务认领和结果写回的保护边界。
 *
 * <p>当前用例以旧代次结果为代表，确认过期 Worker 不会保存草稿或覆盖简历状态。</p>
 */
@ExtendWith(MockitoExtension.class)
class ResumeParseStateServiceTest {

    /** 模拟按用户归属加锁读取及保存简历解析状态。 */
    @Mock
    private ResumeRepository resumeRepository;

    /** 模拟把当前代次解析结果保存为待确认事实草稿。 */
    @Mock
    private ResumeProfileSupport profileSupport;

    /** 使用仓储和画像协作者 Mock 构造的被测状态服务。 */
    private ResumeParseStateService stateService;

    /** 每例重建被测状态服务，避免状态或 Mock 交互跨用例保留。 */
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
