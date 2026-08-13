package com.interviewcoach.interview.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.interviewcoach.interview.application.dto.CreateInterviewRequest;
import com.interviewcoach.interview.application.dto.CreateInterviewResponse;
import com.interviewcoach.interview.application.dto.InterviewReportResponse;
import com.interviewcoach.interview.domain.agent.CoordinatorAgent;
import com.interviewcoach.interview.domain.agent.ReportAgent;
import com.interviewcoach.interview.domain.entity.Interview;
import com.interviewcoach.interview.domain.entity.InterviewMessage;
import com.interviewcoach.interview.domain.entity.InterviewPhase;
import com.interviewcoach.interview.domain.entity.InterviewReport;
import com.interviewcoach.interview.domain.entity.InterviewStatus;
import com.interviewcoach.interview.domain.model.InterviewContext;
import com.interviewcoach.interview.domain.repository.InterviewMessageRepository;
import com.interviewcoach.interview.domain.repository.InterviewReportRepository;
import com.interviewcoach.interview.domain.repository.InterviewRepository;
import com.interviewcoach.interview.infrastructure.desensitize.ReportDesensitizer;
import com.interviewcoach.position.domain.entity.Position;
import com.interviewcoach.position.domain.entity.PositionAuditStatus;
import com.interviewcoach.position.domain.entity.PositionParseStatus;
import com.interviewcoach.position.domain.repository.PositionProfileRepository;
import com.interviewcoach.position.domain.repository.PositionRepository;
import com.interviewcoach.resume.application.service.ResumeProfileAnalysisStateService;
import com.interviewcoach.resume.domain.entity.Resume;
import com.interviewcoach.resume.domain.entity.ResumeProfile;
import com.interviewcoach.resume.domain.entity.ResumeProfileAnalysis;
import com.interviewcoach.resume.domain.entity.ResumeProfileAnalysisStatus;
import com.interviewcoach.resume.domain.model.ResumeProfileAnalysisData;
import com.interviewcoach.resume.domain.model.UserProfileData;
import com.interviewcoach.resume.domain.repository.ResumeProfileRepository;
import com.interviewcoach.resume.domain.repository.ResumeRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 验证面试应用服务对报告缓存、简历事实快照、可用辅助分析和公共岗位的编排边界。
 *
 * <p>仓储、Agent 与脱敏器均以 Mock 隔离；本类关注服务选择哪些数据继续创建或恢复面试，不验证真实数据库、模型或 HTTP 接口。</p>
 */
@ExtendWith(MockitoExtension.class)
class InterviewServiceTest {

    /** 模拟面试聚合的归属查询、保存和继续面试读取。 */
    @Mock
    private InterviewRepository interviewRepository;

    /** 模拟按面试读取已缓存报告的仓储。 */
    @Mock
    private InterviewReportRepository reportRepository;

    /** 模拟恢复面试轮次时按顺序读取历史消息。 */
    @Mock
    private InterviewMessageRepository messageRepository;

    /** 模拟按当前用户读取创建面试所用简历。 */
    @Mock
    private ResumeRepository resumeRepository;

    /** 模拟读取已确认简历事实画像以生成面试快照。 */
    @Mock
    private ResumeProfileRepository resumeProfileRepository;

    /** 模拟读取当前辅助分析及其是否可用于面试的状态视图。 */
    @Mock
    private ResumeProfileAnalysisStateService resumeProfileAnalysisStateService;

    /** 模拟个人或已审核公共岗位的可访问查询及岗位锁相关保存。 */
    @Mock
    private PositionRepository positionRepository;

    /** 模拟读取已确认岗位画像；缺失时服务仍可按当前用例继续创建。 */
    @Mock
    private PositionProfileRepository positionProfileRepository;

    /** 隔离即时报告生成路径的报告 Agent Mock。 */
    @Mock
    private ReportAgent reportAgent;

    /** 模拟面试上下文初始化、首题生成和后续轮次协调。 */
    @Mock
    private CoordinatorAgent coordinatorAgent;

    /** 隔离报告返回前有限字段替换的脱敏器 Mock。 */
    @Mock
    private ReportDesensitizer reportDesensitizer;

    /** 由上述协作者构造、供每个用例直接调用的被测应用服务。 */
    private InterviewService interviewService;
    /** 序列化简历事实和辅助分析快照的测试 JSON 工具，同时传给被测服务。 */
    private ObjectMapper objectMapper;

    /** 每例创建新的 JSON 工具和被测服务，复用由 MockitoExtension 重置的协作者 Mock。 */
    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        interviewService = new InterviewService(
                interviewRepository,
                messageRepository,
                reportRepository,
                resumeRepository,
                resumeProfileRepository,
                resumeProfileAnalysisStateService,
                positionRepository,
                positionProfileRepository,
                reportAgent,
                coordinatorAgent,
                reportDesensitizer,
                objectMapper);
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

    @Test
    void shouldCreateInterviewWithoutOptionalProfileAnalysis() throws Exception {
        Long userId = 1L;
        Resume resume = new Resume();
        resume.setId(2L);
        resume.setUserId(userId);
        Position position = new Position();
        position.setId(3L);
        position.setUserId(userId);
        position.setParseStatus(PositionParseStatus.CONFIRMED);
        position.setJobCategory("TECH");
        UserProfileData profileData = UserProfileData.empty();
        profileData.setSkillTags(List.of("Java"));
        ResumeProfile profile = new ResumeProfile();
        profile.setProfileData(objectMapper.writeValueAsString(profileData));
        InterviewContext context = new InterviewContext();
        context.setCurrentPhase(InterviewPhase.SELF_INTRO);

        when(resumeRepository.findByIdAndUserId(2L, userId)).thenReturn(Optional.of(resume));
        when(positionRepository.findAccessibleById(3L, userId, PositionAuditStatus.APPROVED))
                .thenReturn(Optional.of(position));
        when(resumeProfileRepository.findByResumeIdAndUserId(2L, userId))
                .thenReturn(Optional.of(profile));
        when(resumeProfileAnalysisStateService.loadCurrent(2L, userId)).thenReturn(Optional.empty());
        when(positionProfileRepository.findByPositionId(3L)).thenReturn(Optional.empty());
        when(interviewRepository.save(any(Interview.class))).thenAnswer(invocation -> {
            Interview saved = invocation.getArgument(0);
            saved.setId(10L);
            return saved;
        });
        when(coordinatorAgent.initialize(
                any(Interview.class), any(UserProfileData.class), isNull(), any()))
                .thenReturn(context);
        when(coordinatorAgent.generateFirstQuestion(context)).thenReturn("请介绍一下你的技术背景。");

        CreateInterviewRequest request = new CreateInterviewRequest();
        request.setResumeId(2L);
        request.setPositionId(3L);
        request.setSelectedPhases(List.of("SELF_INTRO"));
        CreateInterviewResponse response = interviewService.createInterview(userId, request);

        ArgumentCaptor<Interview> captor = ArgumentCaptor.forClass(Interview.class);
        verify(interviewRepository, atLeastOnce()).save(captor.capture());
        assertThat(response.getInterviewId()).isEqualTo(10L);
        assertThat(captor.getValue().getUserProfileSnapshot()).isNotBlank();
        assertThat(captor.getValue().getUserProfileAnalysisSnapshot()).isNull();
    }

    @Test
    void shouldSnapshotCurrentProfileAnalysisWhenAvailable() throws Exception {
        Long userId = 1L;
        Resume resume = new Resume();
        resume.setId(2L);
        resume.setUserId(userId);
        Position position = new Position();
        position.setId(3L);
        position.setUserId(userId);
        position.setParseStatus(PositionParseStatus.CONFIRMED);
        position.setJobCategory("TECH");
        UserProfileData profileData = UserProfileData.empty();
        profileData.setSkillTags(List.of("Java"));
        ResumeProfile profile = new ResumeProfile();
        profile.setProfileData(objectMapper.writeValueAsString(profileData));
        ResumeProfileAnalysisData.AnalysisItem strength = new ResumeProfileAnalysisData.AnalysisItem();
        strength.setContent("具备项目实践");
        ResumeProfileAnalysisData analysisData = new ResumeProfileAnalysisData();
        analysisData.setStrengths(List.of(strength));
        ResumeProfileAnalysis analysisEntity = new ResumeProfileAnalysis();
        analysisEntity.setStatus(ResumeProfileAnalysisStatus.SUCCEEDED);
        analysisEntity.setUsableForInterview(true);
        InterviewContext context = new InterviewContext();
        context.setCurrentPhase(InterviewPhase.SELF_INTRO);

        when(resumeRepository.findByIdAndUserId(2L, userId)).thenReturn(Optional.of(resume));
        when(positionRepository.findAccessibleById(3L, userId, PositionAuditStatus.APPROVED))
                .thenReturn(Optional.of(position));
        when(resumeProfileRepository.findByResumeIdAndUserId(2L, userId))
                .thenReturn(Optional.of(profile));
        when(resumeProfileAnalysisStateService.loadCurrent(2L, userId))
                .thenReturn(Optional.of(new ResumeProfileAnalysisStateService.AnalysisView(
                        analysisEntity, analysisData)));
        when(positionProfileRepository.findByPositionId(3L)).thenReturn(Optional.empty());
        when(interviewRepository.save(any(Interview.class))).thenAnswer(invocation -> {
            Interview saved = invocation.getArgument(0);
            saved.setId(10L);
            return saved;
        });
        when(coordinatorAgent.initialize(
                any(Interview.class), any(UserProfileData.class),
                any(ResumeProfileAnalysisData.class), any()))
                .thenReturn(context);
        when(coordinatorAgent.generateFirstQuestion(context)).thenReturn("请介绍一下你的技术背景。");
        CreateInterviewRequest request = new CreateInterviewRequest();
        request.setResumeId(2L);
        request.setPositionId(3L);
        request.setSelectedPhases(List.of("SELF_INTRO"));

        interviewService.createInterview(userId, request);

        ArgumentCaptor<Interview> captor = ArgumentCaptor.forClass(Interview.class);
        verify(interviewRepository, atLeastOnce()).save(captor.capture());
        assertThat(captor.getValue().getUserProfileAnalysisSnapshot()).contains("具备项目实践");
    }

    @Test
    void shouldNotSnapshotInactiveRetainedProfileAnalysis() throws Exception {
        Long userId = 1L;
        Resume resume = new Resume();
        resume.setId(2L);
        resume.setUserId(userId);
        Position position = new Position();
        position.setId(3L);
        position.setUserId(userId);
        position.setParseStatus(PositionParseStatus.CONFIRMED);
        position.setJobCategory("TECH");
        UserProfileData profileData = UserProfileData.empty();
        profileData.setSkillTags(List.of("Java"));
        ResumeProfile profile = new ResumeProfile();
        profile.setProfileData(objectMapper.writeValueAsString(profileData));
        ResumeProfileAnalysisData analysisData = new ResumeProfileAnalysisData();
        ResumeProfileAnalysis analysisEntity = new ResumeProfileAnalysis();
        analysisEntity.setStatus(ResumeProfileAnalysisStatus.FAILED);
        analysisEntity.setUsableForInterview(false);
        InterviewContext context = new InterviewContext();
        context.setCurrentPhase(InterviewPhase.SELF_INTRO);

        when(resumeRepository.findByIdAndUserId(2L, userId)).thenReturn(Optional.of(resume));
        when(positionRepository.findAccessibleById(3L, userId, PositionAuditStatus.APPROVED))
                .thenReturn(Optional.of(position));
        when(resumeProfileRepository.findByResumeIdAndUserId(2L, userId))
                .thenReturn(Optional.of(profile));
        when(resumeProfileAnalysisStateService.loadCurrent(2L, userId))
                .thenReturn(Optional.of(new ResumeProfileAnalysisStateService.AnalysisView(
                        analysisEntity, analysisData, true, false)));
        when(positionProfileRepository.findByPositionId(3L)).thenReturn(Optional.empty());
        when(interviewRepository.save(any(Interview.class))).thenAnswer(invocation -> {
            Interview saved = invocation.getArgument(0);
            saved.setId(10L);
            return saved;
        });
        when(coordinatorAgent.initialize(
                any(Interview.class), any(UserProfileData.class), isNull(), any()))
                .thenReturn(context);
        when(coordinatorAgent.generateFirstQuestion(context)).thenReturn("请介绍一下你的技术背景。");
        CreateInterviewRequest request = new CreateInterviewRequest();
        request.setResumeId(2L);
        request.setPositionId(3L);
        request.setSelectedPhases(List.of("SELF_INTRO"));

        interviewService.createInterview(userId, request);

        ArgumentCaptor<Interview> captor = ArgumentCaptor.forClass(Interview.class);
        verify(interviewRepository, atLeastOnce()).save(captor.capture());
        assertThat(captor.getValue().getUserProfileAnalysisSnapshot()).isNull();
    }

    @Test
    void shouldContinueExistingInterviewFromStoredAnalysisSnapshot() throws Exception {
        Long userId = 1L;
        Long interviewId = 7L;
        Interview interview = new Interview();
        interview.setId(interviewId);
        interview.setUserId(userId);
        interview.setStatus(InterviewStatus.IN_PROGRESS);
        interview.setCurrentPhase(InterviewPhase.SELF_INTRO);
        interview.setUserProfileSnapshot("{}");
        interview.setUserProfileAnalysisSnapshot(
                "{\"strengths\":[],\"verificationPoints\":[],\"skillAssessments\":[]}");
        interview.setPositionProfileSnapshot("{}");
        InterviewMessage lastQuestion = new InterviewMessage();
        lastQuestion.setRole("interviewer");
        lastQuestion.setContent("请介绍一下自己。");
        InterviewContext context = new InterviewContext();
        context.setCurrentPhase(InterviewPhase.SELF_INTRO);
        CoordinatorAgent.TurnResult turnResult = new CoordinatorAgent.TurnResult();
        turnResult.setPhase(InterviewPhase.SELF_INTRO);
        turnResult.setDepth(1);
        turnResult.setQuestion("请继续介绍项目经验。");
        when(interviewRepository.findByIdAndUserId(interviewId, userId))
                .thenReturn(Optional.of(interview));
        when(messageRepository.findByInterviewIdOrderBySeqNoAsc(interviewId))
                .thenReturn(List.of(lastQuestion));
        when(coordinatorAgent.initialize(
                eq(interview),
                any(UserProfileData.class),
                any(ResumeProfileAnalysisData.class),
                any()))
                .thenReturn(context);
        when(coordinatorAgent.coordinate(
                context, interview, "请介绍一下自己。", "我有三年 Java 经验。"))
                .thenReturn(turnResult);

        interviewService.submitAnswer(
                userId, interviewId, "我有三年 Java 经验。");

        verify(resumeProfileAnalysisStateService, never()).loadCurrent(any(), any());
        verify(coordinatorAgent).initialize(
                eq(interview),
                any(UserProfileData.class),
                any(ResumeProfileAnalysisData.class),
                any());
    }

    @Test
    void shouldCreateInterviewFromApprovedPublicPositionWithoutLockingSharedTemplate() throws Exception {
        Long userId = 1L;
        Resume resume = new Resume();
        resume.setId(2L);
        resume.setUserId(userId);
        Position publicPosition = new Position();
        publicPosition.setId(3L);
        publicPosition.setUserId(99L);
        publicPosition.setIsPublic(true);
        publicPosition.setAuditStatus(PositionAuditStatus.APPROVED);
        publicPosition.setParseStatus(PositionParseStatus.CONFIRMED);
        publicPosition.setJobCategory("TECH");
        UserProfileData profileData = UserProfileData.empty();
        ResumeProfile profile = new ResumeProfile();
        profile.setProfileData(objectMapper.writeValueAsString(profileData));
        InterviewContext context = new InterviewContext();
        context.setCurrentPhase(InterviewPhase.SELF_INTRO);

        when(resumeRepository.findByIdAndUserId(2L, userId)).thenReturn(Optional.of(resume));
        when(positionRepository.findAccessibleById(3L, userId, PositionAuditStatus.APPROVED))
                .thenReturn(Optional.of(publicPosition));
        when(resumeProfileRepository.findByResumeIdAndUserId(2L, userId))
                .thenReturn(Optional.of(profile));
        when(resumeProfileAnalysisStateService.loadCurrent(2L, userId)).thenReturn(Optional.empty());
        when(positionProfileRepository.findByPositionId(3L)).thenReturn(Optional.empty());
        when(interviewRepository.save(any(Interview.class))).thenAnswer(invocation -> {
            Interview saved = invocation.getArgument(0);
            saved.setId(10L);
            return saved;
        });
        when(coordinatorAgent.initialize(
                any(Interview.class), any(UserProfileData.class), isNull(), any()))
                .thenReturn(context);
        when(coordinatorAgent.generateFirstQuestion(context)).thenReturn("请介绍一下你的技术背景。");

        CreateInterviewRequest request = new CreateInterviewRequest();
        request.setResumeId(2L);
        request.setPositionId(3L);
        request.setSelectedPhases(List.of("SELF_INTRO"));

        CreateInterviewResponse response = interviewService.createInterview(userId, request);

        assertThat(response.getInterviewId()).isEqualTo(10L);
        assertThat(publicPosition.getLockInterviewId()).isNull();
        verify(positionRepository, never()).save(publicPosition);
    }
}
