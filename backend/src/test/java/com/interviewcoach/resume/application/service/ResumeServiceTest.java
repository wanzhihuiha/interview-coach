package com.interviewcoach.resume.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.interviewcoach.common.exception.BusinessException;
import com.interviewcoach.resume.application.dto.ResumeDetailResponse;
import com.interviewcoach.resume.application.dto.ResumeListResponse;
import com.interviewcoach.resume.application.dto.ResumeParseStatusResponse;
import com.interviewcoach.resume.application.dto.ResumeProfileResponse;
import com.interviewcoach.resume.application.event.ResumeAiQuotaReservation;
import com.interviewcoach.resume.application.event.ResumeAiTaskLease;
import com.interviewcoach.resume.domain.entity.Resume;
import com.interviewcoach.resume.domain.entity.ResumeParseStatus;
import com.interviewcoach.resume.domain.entity.ResumeProfile;
import com.interviewcoach.resume.domain.entity.ResumeProfileAnalysis;
import com.interviewcoach.resume.domain.entity.ResumeProfileAnalysisMode;
import com.interviewcoach.resume.domain.entity.ResumeProfileAnalysisStatus;
import com.interviewcoach.resume.domain.entity.ResumeProfileDraft;
import com.interviewcoach.resume.domain.model.UserProfileData;
import com.interviewcoach.resume.domain.repository.ResumeProfileAnalysisRepository;
import com.interviewcoach.resume.domain.repository.ResumeProfileDraftRepository;
import com.interviewcoach.resume.domain.repository.ResumeProfileRepository;
import com.interviewcoach.resume.domain.repository.ResumeRepository;
import com.interviewcoach.resume.infrastructure.redis.ResumeAiQuotaService;
import com.interviewcoach.resume.infrastructure.redis.ResumeAiTaskAdmissionService;
import com.interviewcoach.resume.infrastructure.redis.ResumeAiTaskLeaseRunner;
import com.interviewcoach.resume.infrastructure.redis.ResumeUserMutationLock;
import com.interviewcoach.resume.infrastructure.storage.FileStorageService;
import com.interviewcoach.user.application.service.ConsentService;
import java.time.LocalDate;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * 验证简历应用服务在 Spring/H2 测试环境中的列表、详情、草稿确认、画像读取、重解析和删除编排。
 *
 * <p>简历与画像仓储使用事务内真实数据，Redis、同意、文件、用户锁和 AI 准入协作者以 Mock 隔离；本类不启动 Redis 或执行真实文件操作。</p>
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ResumeServiceTest {

    /** Spring 容器装配的被测简历应用服务。 */
    @Autowired
    private ResumeService resumeService;

    /** 用于在测试事务内模拟解析任务认领、完成与失败的真实状态服务。 */
    @Autowired
    private ResumeParseStateService parseStateService;

    /** 保存并读回简历聚合状态的 H2 测试仓储。 */
    @Autowired
    private ResumeRepository resumeRepository;

    /** 保存并读回已确认事实画像的 H2 测试仓储。 */
    @Autowired
    private ResumeProfileRepository profileRepository;

    /** 保存并读回待用户确认草稿的 H2 测试仓储。 */
    @Autowired
    private ResumeProfileDraftRepository draftRepository;

    /** 保存并读回辅助分析结果与当前任务元数据的 H2 测试仓储。 */
    @Autowired
    private ResumeProfileAnalysisRepository analysisRepository;

    /** 为草稿、正式画像及分析夹具读写 JSON 的容器内序列化器。 */
    @Autowired
    private ObjectMapper objectMapper;

    /** 替代真实 Redis 访问，避免服务测试连接外部缓存。 */
    @MockBean
    private StringRedisTemplate redisTemplate;

    /** 隔离隐私协议前置校验，使场景聚焦简历编排。 */
    @MockBean
    private ConsentService consentService;

    /** 隔离真实文件删除副作用。 */
    @MockBean
    private FileStorageService fileStorageService;

    /** 模拟用户级变更锁，并在当前测试事务线程内直接执行受保护动作。 */
    @MockBean
    private ResumeUserMutationLock mutationLock;

    /** 模拟 AI 双级许可；默认拒绝可选首次分析，手工重解析场景再显式放行。 */
    @MockBean
    private ResumeAiTaskAdmissionService admissionService;

    /** 模拟手工 AI 任务的每日额度预留。 */
    @MockBean
    private ResumeAiQuotaService quotaService;

    /** 隔离未进入 Worker 时的许可释放协作者。 */
    @MockBean
    private ResumeAiTaskLeaseRunner leaseRunner;

    /** 每例让用户锁包装的动作在本线程执行，并默认拒绝可选 AI 许可，防止测试产生异步任务。 */
    @BeforeEach
    void allowUserMutationWithinTestTransaction() {
        doAnswer(invocation -> ((Supplier<?>) invocation.getArgument(1)).get())
                .when(mutationLock).execute(anyLong(), any());
        doThrow(new BusinessException(
                        ResumeErrorCode.USER_AI_CONCURRENCY_LIMIT,
                        "当前用户的 AI 任务数已达上限"))
                .when(admissionService).acquire(anyLong(), anyLong());
    }

    @Test
    void shouldListResumesForUser() throws Exception {
        Long userId = 1L;
        Resume confirmed = createResume(userId, "简历1.pdf", ResumeParseStatus.CONFIRMED);
        saveConfirmedProfile(confirmed, validProfile("Java"));
        createResume(userId, "简历2.txt", ResumeParseStatus.PENDING);

        ResumeListResponse response = resumeService.listResumes(userId, 0, 10);

        assertThat(response.getContent()).hasSize(2);
        assertThat(response.getTotalElements()).isEqualTo(2);
        assertThat(response.getContent()).allSatisfy(item ->
                assertThat(item.getJobCategoryLabel()).isEqualTo("技术类"));
        assertThat(response.getContent()).anySatisfy(item -> {
            assertThat(item.getResumeId()).isEqualTo(confirmed.getId());
            assertThat(item.getHasConfirmedProfile()).isTrue();
        });
    }

    @Test
    void shouldGetResumeDetailFromCurrentDraft() throws Exception {
        Long userId = 1L;
        Resume resume = createResume(userId, "简历1.pdf", ResumeParseStatus.PENDING_CONFIRM);
        saveDraft(resume, validProfile("Spring Boot"));

        ResumeDetailResponse detail = resumeService.getResumeDetail(userId, resume.getId());

        assertThat(detail.getFileName()).isEqualTo("简历1.pdf");
        assertThat(detail.getStatusLabel()).isEqualTo("待确认");
        assertThat(detail.getParsedData().getSkillTags()).containsExactly("Spring Boot");
        assertThat(detail.getHasConfirmedProfile()).isFalse();
    }

    @Test
    void shouldAllowEmptyParsedDraftButRejectEmptyConfirmation() {
        Long userId = 1L;
        Resume resume = createResume(userId, "简历1.pdf", ResumeParseStatus.PENDING);

        assertThat(parseStateService.start(resume.getId(), userId, 1L)).isNotNull();
        assertThat(parseStateService.complete(
                resume.getId(), userId, 1L, UserProfileData.empty())).isTrue();
        assertThat(draftRepository.findByResumeIdAndUserId(resume.getId(), userId)).isPresent();

        assertThatThrownBy(() -> resumeService.confirmResume(
                userId, resume.getId(), 1L, UserProfileData.empty()))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getCode())
                        .isEqualTo(ResumeErrorCode.PROFILE_DATA_INVALID));
        assertThat(profileRepository.findByResumeIdAndUserId(resume.getId(), userId)).isEmpty();
    }

    @Test
    void shouldConfirmFactsWithoutCreatingOptionalInitialWhenPermitIsUnavailable() throws Exception {
        Long userId = 1L;
        Resume resume = createResume(userId, "简历1.pdf", ResumeParseStatus.PENDING_CONFIRM);
        saveDraft(resume, validProfile("Java"));
        UserProfileData confirmedData = validProfile("Spring Boot");

        assertThat(profileRepository.findByResumeIdAndUserId(resume.getId(), userId)).isEmpty();

        resumeService.confirmResume(userId, resume.getId(), resume.getParseGeneration(), confirmedData);

        Resume updated = resumeRepository.findById(resume.getId()).orElseThrow();
        ResumeProfile profile = profileRepository.findByResumeIdAndUserId(resume.getId(), userId).orElseThrow();
        assertThat(updated.getParseStatus()).isEqualTo(ResumeParseStatus.CONFIRMED);
        assertThat(draftRepository.findByResumeIdAndUserId(resume.getId(), userId)).isEmpty();
        assertThat(objectMapper.readValue(profile.getProfileData(), UserProfileData.class).getSkillTags())
                .containsExactly("Spring Boot");
        assertThat(profile.getProfileHash()).hasSize(64);
        assertThat(analysisRepository.findByResumeIdAndUserId(resume.getId(), userId)).isEmpty();
    }

    @Test
    void shouldThrowExceptionWhenConfirmingNonPendingConfirmResume() {
        Long userId = 1L;
        Resume resume = createResume(userId, "简历1.pdf", ResumeParseStatus.PENDING);

        assertThatThrownBy(() -> resumeService.confirmResume(
                userId, resume.getId(), resume.getParseGeneration(), validProfile("Java")))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getCode())
                        .isEqualTo(ResumeErrorCode.RESUME_STATUS_INVALID));
    }

    @Test
    void shouldGetConfirmedResumeProfile() throws Exception {
        Long userId = 1L;
        Resume resume = createResume(userId, "简历1.pdf", ResumeParseStatus.CONFIRMED);
        saveConfirmedProfile(resume, validProfile("MySQL"));

        ResumeProfileResponse response = resumeService.getResumeProfile(userId, resume.getId());

        assertThat(response.getProfile().getSkillTags()).containsExactly("MySQL");
        assertThat(response.getConfirmedProfile().getSkillTags()).containsExactly("MySQL");
        assertThat(response.getDraftProfile()).isNull();
        assertThat(response.getHasConfirmedProfile()).isTrue();
        assertThat(response.getExperienceLevel()).isEqualTo("JUNIOR");
        assertThat(response.getExperienceLevelLabel()).isEqualTo("初级");
        assertThat(response.getAnalysisUsableForInterview()).isFalse();
        assertThat(response.getAnalysisRefineAllowed()).isFalse();
        assertThat(response.getAnalysisTaskGeneration()).isNull();
        assertThat(response.getAnalysisMode()).isNull();
    }

    @Test
    void shouldExposeInactiveRetainedAnalysisWithLatestTaskMetadata() throws Exception {
        Long userId = 1L;
        Resume resume = createResume(userId, "简历1.pdf", ResumeParseStatus.CONFIRMED);
        saveConfirmedProfile(resume, validProfile("MySQL"));
        ResumeProfileAnalysis analysis = new ResumeProfileAnalysis();
        analysis.setResumeId(resume.getId());
        analysis.setUserId(userId);
        analysis.setSourceProfileHash("old-hash");
        analysis.setAnalysisData("{\"strengths\":[],\"verificationPoints\":[],\"skillAssessments\":[]}");
        analysis.setStatus(ResumeProfileAnalysisStatus.FAILED);
        analysis.setTaskGeneration(4L);
        analysis.setTaskProfileHash("a".repeat(64));
        analysis.setTaskMode(ResumeProfileAnalysisMode.REGENERATE);
        analysis.setPromptVersion("resume-insights-v2");
        analysis.setUsableForInterview(false);
        analysisRepository.save(analysis);

        ResumeProfileResponse response = resumeService.getResumeProfile(userId, resume.getId());

        assertThat(response.getAnalysis()).isNotNull();
        assertThat(response.getAnalysisStatus()).isEqualTo("FAILED");
        assertThat(response.getAnalysisUsableForInterview()).isFalse();
        assertThat(response.getAnalysisRefineAllowed()).isFalse();
        assertThat(response.getAnalysisTaskGeneration()).isEqualTo(4L);
        assertThat(response.getAnalysisMode()).isEqualTo("REGENERATE");
    }

    @Test
    void shouldQueueReparseAndKeepConfirmedProfile() throws Exception {
        Long userId = 1L;
        Resume resume = createResume(userId, "简历1.pdf", ResumeParseStatus.CONFIRMED);
        saveConfirmedProfile(resume, validProfile("Java"));
        allowManualTaskSubmission(userId, resume.getId());

        resumeService.reparseResume(userId, resume.getId());

        ResumeParseStatusResponse status = resumeService.getParseStatus(userId, resume.getId());
        assertThat(status.getStatus()).isEqualTo("PENDING");
        assertThat(status.getParseGeneration()).isEqualTo(2L);
        assertThat(status.getHasConfirmedProfile()).isTrue();
        assertThat(profileRepository.findByResumeIdAndUserId(resume.getId(), userId)).isPresent();
    }

    @Test
    void shouldKeepConfirmedProfileWhenReparseFails() throws Exception {
        Long userId = 1L;
        Resume resume = createResume(userId, "简历1.pdf", ResumeParseStatus.CONFIRMED);
        saveConfirmedProfile(resume, validProfile("Java"));
        allowManualTaskSubmission(userId, resume.getId());

        resumeService.reparseResume(userId, resume.getId());
        ResumeParseStateService.ParseInput input = parseStateService.start(resume.getId(), userId, 2L);
        parseStateService.fail(resume.getId(), userId, 2L, "PROFILE_RESOLUTION_FAILED", "简历事实分析失败");

        assertThat(input).isNotNull();
        assertThat(resumeRepository.findById(resume.getId()).orElseThrow().getParseStatus())
                .isEqualTo(ResumeParseStatus.PARSE_FAILED);
        assertThat(profileRepository.findByResumeIdAndUserId(resume.getId(), userId)).isPresent();
    }

    @Test
    void shouldRejectStaleDraftUpdate() throws Exception {
        Long userId = 1L;
        Resume resume = createResume(userId, "简历1.pdf", ResumeParseStatus.PENDING_CONFIRM);
        saveDraft(resume, validProfile("Java"));

        assertThatThrownBy(() -> resumeService.updateProfileDraft(
                userId, resume.getId(), resume.getParseGeneration() - 1, validProfile("Redis")))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getCode())
                        .isEqualTo(ResumeErrorCode.PROFILE_DRAFT_STALE));

        ResumeProfileDraft unchanged = draftRepository
                .findByResumeIdAndUserId(resume.getId(), userId).orElseThrow();
        assertThat(objectMapper.readValue(unchanged.getProfileData(), UserProfileData.class).getSkillTags())
                .containsExactly("Java");
    }

    @Test
    void shouldDeleteResumeSuccessfully() {
        Long userId = 1L;
        Resume resume = createResume(userId, "简历1.pdf", ResumeParseStatus.PENDING);

        resumeService.deleteResume(userId, resume.getId());

        assertThat(resumeRepository.findById(resume.getId())).isEmpty();
    }

    @Test
    void shouldThrowExceptionWhenDeletingLockedResume() {
        Long userId = 1L;
        Resume resume = createResume(userId, "简历1.pdf", ResumeParseStatus.CONFIRMED);
        resume.setLockInterviewId(100L);
        resumeRepository.save(resume);

        assertThatThrownBy(() -> resumeService.deleteResume(userId, resume.getId()))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getCode())
                        .isEqualTo(ResumeErrorCode.RESUME_LOCKED_FOR_DELETE));
    }

    @Test
    void shouldRejectOtherUserDraftUpdate() throws Exception {
        Long ownerId = 1L;
        Long otherUserId = 2L;
        Resume resume = createResume(ownerId, "简历1.pdf", ResumeParseStatus.PENDING_CONFIRM);
        saveDraft(resume, validProfile("Java"));

        assertThatThrownBy(() -> resumeService.updateProfileDraft(
                otherUserId, resume.getId(), resume.getParseGeneration(), validProfile("Redis")))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getCode())
                        .isEqualTo(ResumeErrorCode.RESUME_NOT_FOUND));
    }

    private Resume createResume(Long userId, String name, ResumeParseStatus status) {
        Resume resume = new Resume();
        resume.setUserId(userId);
        resume.setResumeName(name);
        resume.setFileType(name.endsWith(".pdf") ? "PDF" : "TXT");
        resume.setFileSize(1024L);
        resume.setFilePath("/tmp/" + name);
        resume.setParseStatus(status);
        resume.setParseGeneration(1L);
        resume.setJobCategory("TECH");
        return resumeRepository.save(resume);
    }

    private ResumeProfileDraft saveDraft(Resume resume, UserProfileData data) throws Exception {
        ResumeProfileDraft draft = new ResumeProfileDraft();
        draft.setResumeId(resume.getId());
        draft.setUserId(resume.getUserId());
        draft.setProfileData(objectMapper.writeValueAsString(data));
        draft.setExperienceLevel("JUNIOR");
        draft.setParseGeneration(resume.getParseGeneration());
        return draftRepository.save(draft);
    }

    private ResumeProfile saveConfirmedProfile(Resume resume, UserProfileData data) throws Exception {
        ResumeProfile profile = new ResumeProfile();
        profile.setResumeId(resume.getId());
        profile.setUserId(resume.getUserId());
        profile.setProfileData(objectMapper.writeValueAsString(data));
        profile.setExperienceLevel("JUNIOR");
        profile.setProfileHash("a".repeat(64));
        return profileRepository.save(profile);
    }

    private void allowManualTaskSubmission(Long userId, Long resumeId) {
        doReturn(new ResumeAiTaskLease("user-permit", "resume-permit"))
                .when(admissionService).acquire(userId, resumeId);
        when(quotaService.reserve(userId)).thenReturn(new ResumeAiQuotaReservation(
                LocalDate.of(2026, 7, 30), "quota-token"));
    }

    private UserProfileData validProfile(String skill) {
        UserProfileData data = UserProfileData.empty();
        data.setSkillTags(List.of(skill));
        return data;
    }
}
