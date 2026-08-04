package com.interviewcoach.interview.application.service;

import static com.interviewcoach.interview.application.service.InterviewErrorCode.*;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.interviewcoach.common.exception.BusinessException;
import com.interviewcoach.interview.application.dto.CreateInterviewRequest;
import com.interviewcoach.interview.application.dto.CreateInterviewResponse;
import com.interviewcoach.interview.application.dto.InterviewDetailResponse;
import com.interviewcoach.interview.application.dto.InterviewMessageResponse;
import com.interviewcoach.interview.application.dto.InterviewReportResponse;
import com.interviewcoach.interview.application.service.InterviewCreationStateService.PreparedInterview;
import com.interviewcoach.interview.application.service.InterviewTurnStateService.ReservedTurn;
import com.interviewcoach.interview.domain.agent.CoordinatorAgent;
import com.interviewcoach.interview.domain.agent.CoordinatorAgent.TurnResult;
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
import com.interviewcoach.position.domain.model.PositionProfileData;
import com.interviewcoach.position.domain.repository.PositionRepository;
import com.interviewcoach.resume.domain.entity.Resume;
import com.interviewcoach.resume.domain.model.UserProfileData;
import com.interviewcoach.resume.domain.model.ResumeProfileAnalysisData;
import com.interviewcoach.resume.domain.repository.ResumeRepository;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 面试应用服务。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InterviewService {

    private final InterviewRepository interviewRepository;
    private final InterviewMessageRepository messageRepository;
    private final InterviewReportRepository reportRepository;
    private final ResumeRepository resumeRepository;
    private final PositionRepository positionRepository;
    private final InterviewCreationStateService creationStateService;
    private final InterviewTurnStateService turnStateService;
    private final ReportAgent reportAgent;
    private final CoordinatorAgent coordinatorAgent;
    private final ReportDesensitizer reportDesensitizer;
    private final ObjectMapper objectMapper;

    /**
     * 使用当前正式事实画像创建面试并固定快照；当前可用的 AI 分析一并快照，缺失时不阻断。
     */
    public CreateInterviewResponse createInterview(Long userId, CreateInterviewRequest request) {
        if (request.getSelectedPhases() == null || request.getSelectedPhases().isEmpty()) {
            throw new BusinessException(NO_PHASE_SELECTED.getCode(), "未选择任何环节");
        }

        List<InterviewPhase> selectedPhases = parseAndSortPhases(request.getSelectedPhases());

        PreparedInterview prepared = creationStateService.prepare(
                userId,
                request.getResumeId(),
                request.getPositionId(),
                selectedPhases);
        Interview interview = prepared.interview();
        String firstQuestion;
        try {
            InterviewContext context = coordinatorAgent.initialize(
                    interview,
                    prepared.userProfile(),
                    prepared.userProfileAnalysis(),
                    prepared.positionProfile());
            firstQuestion = coordinatorAgent.generateFirstQuestion(context);
            if (firstQuestion == null || firstQuestion.isBlank()) {
                throw new BusinessException(
                        INTERVIEW_INITIALIZATION_FAILED.getCode(), "首题生成失败");
            }
            interview = creationStateService.completeFirstQuestion(
                    userId, interview.getId(), firstQuestion, context);
        } catch (RuntimeException e) {
            compensateFailedCreation(userId, interview.getId(), e);
            throw e;
        }

        CreateInterviewResponse response = new CreateInterviewResponse();
        response.setInterviewId(interview.getId());
        response.setStatus(interview.getStatus().name());
        response.setStatusLabel(interview.getStatus().getDisplayName());
        List<String> phaseCodes = selectedPhases.stream().map(InterviewPhase::name).toList();
        response.setSelectedPhases(phaseCodes);
        response.setCurrentPhase(interview.getCurrentPhase().name());
        response.setCurrentPhaseLabel(interview.getCurrentPhase().getDisplayName());
        response.setFirstQuestion(firstQuestion);
        response.setPhaseOrder(phaseCodes);
        response.setPhaseLabels(toPhaseLabels(phaseCodes));
        return response;
    }

    /**
     * 首题生成或短事务写回失败时独立补偿；补偿异常不能覆盖原始失败。
     */
    private void compensateFailedCreation(
            Long userId, Long interviewId, RuntimeException originalFailure) {
        try {
            creationStateService.interruptPreparedInterview(userId, interviewId);
        } catch (RuntimeException compensationFailure) {
            originalFailure.addSuppressed(compensationFailure);
            log.error(
                    "[InterviewService] 面试创建补偿失败: interviewId={}, errorType={}",
                    interviewId,
                    compensationFailure.getClass().getSimpleName(),
                    compensationFailure);
        }
    }

    /**
     * 获取面试详情。
     */
    @Transactional(readOnly = true)
    public InterviewDetailResponse getInterview(Long userId, Long interviewId) {
        Interview interview = findInterview(userId, interviewId);
        return toDetailResponse(interview);
    }

    /**
     * 使用创建面试时固定的事实和可选分析快照处理回答，避免后续画像变化影响本场面试。
     * 该方法为非流式内部方法，由 Controller 包装 SSE。
     */
    public TurnResult submitAnswer(Long userId, Long interviewId, String answer) {
        if (answer == null || answer.isBlank()) {
            throw new BusinessException(ANSWER_INVALID.getCode(), "回答内容无效");
        }
        if (answer.length() > 5000) {
            throw new BusinessException(ANSWER_INVALID.getCode(), "回答内容过长");
        }

        ReservedTurn reserved = turnStateService.reserve(userId, interviewId);
        try {
            Interview interview = reserved.interview();
            UserProfileData userProfile = requireSnapshotJson(
                    interview.getUserProfileSnapshot(), UserProfileData.class);
            ResumeProfileAnalysisData userProfileAnalysis = parseJson(
                    interview.getUserProfileAnalysisSnapshot(), ResumeProfileAnalysisData.class);
            PositionProfileData positionProfile = requireSnapshotJson(
                    interview.getPositionProfileSnapshot(), PositionProfileData.class);
            InterviewContext context = coordinatorAgent.initialize(
                    interview, userProfile, userProfileAnalysis, positionProfile);
            syncContext(context, interview);

            TurnResult result = coordinatorAgent.coordinate(
                    context, interview, reserved.lastQuestion(), answer);
            turnStateService.complete(userId, reserved, answer, context, result);
            return result;
        } catch (RuntimeException failure) {
            releaseFailedTurn(userId, interviewId, reserved.reservationToken(), failure);
            throw failure;
        }
    }

    /**
     * turn 失败时释放精确 token；补偿异常不能覆盖模型或写回的原始失败。
     */
    private void releaseFailedTurn(
            Long userId,
            Long interviewId,
            String reservationToken,
            RuntimeException originalFailure) {
        try {
            turnStateService.release(userId, interviewId, reservationToken);
        } catch (RuntimeException releaseFailure) {
            originalFailure.addSuppressed(releaseFailure);
            log.error(
                    "[InterviewService] 回答 reservation 释放失败: interviewId={}, errorType={}",
                    interviewId,
                    releaseFailure.getClass().getSimpleName(),
                    releaseFailure);
        }
    }

    /**
     * 主动结束面试。
     */
    @Transactional
    public InterviewDetailResponse endInterview(Long userId, Long interviewId) {
        Interview interview = findInterviewForUpdate(userId, interviewId);
        if (interview.getStatus() == InterviewStatus.IN_PROGRESS) {
            coordinatorAgent.endInterview(interview, true);
            interview.setPendingQuestion(null);
            interviewRepository.save(interview);
        }
        unlockResumeAndPosition(interview);
        return toDetailResponse(interview);
    }

    /**
     * 查询当前用户的面试列表。
     */
    @Transactional(readOnly = true)
    public List<InterviewDetailResponse> listInterviews(Long userId) {
        return interviewRepository.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(this::toDetailResponse)
                .toList();
    }

    /**
     * 获取消息列表。
     */
    @Transactional(readOnly = true)
    public List<InterviewMessageResponse> listMessages(Long userId, Long interviewId) {
        findInterview(userId, interviewId);
        return messageRepository.findByInterviewIdOrderBySeqNoAsc(interviewId).stream()
                .map(this::toMessageResponse)
                .toList();
    }

    /**
     * 获取评估报告。
     *
     * <p>优先从数据库读取已保存的报告，避免重复生成；不存在时生成、脱敏并持久化。</p>
     */
    @Transactional
    public InterviewReportResponse getReport(Long userId, Long interviewId) {
        Interview interview = findInterview(userId, interviewId);

        // 1. 优先读取已持久化的报告
        InterviewReport cached = reportRepository.findByInterviewId(interviewId).orElse(null);
        if (cached != null) {
            log.info("[InterviewService] 命中已保存报告 interviewId={}", interviewId);
            return toReportResponse(cached);
        }

        // 2. 生成报告
        List<InterviewMessage> messages = messageRepository.findByInterviewIdOrderBySeqNoAsc(interviewId);
        PositionProfileData positionProfile = requireSnapshotJson(
                interview.getPositionProfileSnapshot(), PositionProfileData.class);

        InterviewReportResponse report = new InterviewReportResponse();
        report.setInterviewId(interviewId);

        int totalScore = calculateOverallScore(messages);
        report.setOverallScore(totalScore);
        report.setGrade(calculateGrade(totalScore));

        Map<String, InterviewReportResponse.PhaseSummary> phases = new LinkedHashMap<>();
        for (InterviewPhase phase : InterviewPhase.values()) {
            long count = messages.stream().filter(m -> m.getPhase().equals(phase.name()) && "interviewer".equals(m.getRole())).count();
            InterviewReportResponse.PhaseSummary summary = new InterviewReportResponse.PhaseSummary();
            summary.setPhaseLabel(phase.getDisplayName());
            summary.setQuestionCount((int) count);
            summary.setCompleted(phase == InterviewPhase.ENDING || interview.getCurrentPhase().getOrder() > phase.getOrder());
            phases.put(phase.name(), summary);
        }
        report.setPhases(phases);

        InterviewReportResponse.DimensionScores dimensions = new InterviewReportResponse.DimensionScores();
        dimensions.setTechnicalDepth(totalScore);
        dimensions.setTechnicalBreadth(totalScore - 3);
        dimensions.setPracticalExperience(totalScore - 1);
        dimensions.setExpression(totalScore - 2);
        dimensions.setLearningAbility(totalScore - 4);
        report.setDimensions(dimensions);

        // 使用 ReportAgent 基于真实问答生成具体优劣势
        ReportAgent.AnalysisResult analysis = reportAgent.analyze(messages, positionProfile);
        report.setStrengths(analysis.strengths().isEmpty()
                ? List.of("参与面试态度积极") : analysis.strengths());
        report.setWeaknesses(analysis.weaknesses().isEmpty()
                ? List.of("建议补充更多实践案例和原理细节") : analysis.weaknesses());
        report.setKeyEvents(List.of());
        report.setConclusion("本次面试已完成，候选人整体表现" + report.getGrade()
                + "。" + String.join("；", analysis.weaknesses()));
        report.setMdContent(buildReportMarkdown(report));

        // 3. 脱敏：隐去公司信息、候选人姓名等敏感内容
        InterviewReportResponse desensitized = reportDesensitizer.desensitize(
                report, interview.getCompanyNameSnapshot(), null);

        // 4. 保存并返回
        InterviewReport entity = toReportEntity(userId, interviewId, desensitized);
        reportRepository.save(entity);
        log.info("[InterviewService] 报告已生成并保存 interviewId={}", interviewId);

        return desensitized;
    }

    private String buildReportMarkdown(InterviewReportResponse report) {
        StringBuilder md = new StringBuilder();
        md.append("# 面试评估报告\n\n");
        md.append("## 一、综合评分\n\n");
        md.append("- 综合评分：").append(report.getOverallScore()).append("\n");
        md.append("- 等级：").append(report.getGrade()).append("\n\n");

        md.append("## 二、维度得分\n\n");
        InterviewReportResponse.DimensionScores d = report.getDimensions();
        md.append("| 维度 | 得分 |\n");
        md.append("|---|---|\n");
        md.append("| 技术深度 | ").append(d.getTechnicalDepth()).append(" |\n");
        md.append("| 技术广度 | ").append(d.getTechnicalBreadth()).append(" |\n");
        md.append("| 实践经验 | ").append(d.getPracticalExperience()).append(" |\n");
        md.append("| 表达能力 | ").append(d.getExpression()).append(" |\n");
        md.append("| 学习能力 | ").append(d.getLearningAbility()).append(" |\n\n");

        md.append("## 三、环节完成情况\n\n");
        md.append("| 环节 | 题目数 | 状态 |\n");
        md.append("|---|---|---|\n");
        for (Map.Entry<String, InterviewReportResponse.PhaseSummary> entry : report.getPhases().entrySet()) {
            InterviewReportResponse.PhaseSummary summary = entry.getValue();
            String phaseLabel = summary.getPhaseLabel() != null
                    ? summary.getPhaseLabel() : InterviewPhase.displayNameOf(entry.getKey());
            md.append("| ").append(phaseLabel).append(" | ")
                    .append(summary.getQuestionCount()).append(" | ")
                    .append(Boolean.TRUE.equals(summary.getCompleted()) ? "已完成" : "未完成").append(" |\n");
        }
        md.append("\n");

        md.append("## 四、优势知识点\n\n");
        for (String strength : report.getStrengths()) {
            md.append("- ").append(strength).append("\n");
        }
        md.append("\n");

        md.append("## 五、薄弱知识点\n\n");
        for (String weakness : report.getWeaknesses()) {
            md.append("- ").append(weakness).append("\n");
        }
        md.append("\n");

        md.append("## 六、综合评价\n\n");
        md.append(report.getConclusion()).append("\n");
        return md.toString();
    }

    private Interview findInterview(Long userId, Long interviewId) {
        return interviewRepository.findByIdAndUserId(interviewId, userId)
                .orElseThrow(() -> new BusinessException(INTERVIEW_NOT_FOUND.getCode(), "面试不存在"));
    }

    /**
     * 锁定本人面试，确保回答、主动结束和创建写回不会并发覆盖状态。
     */
    private Interview findInterviewForUpdate(Long userId, Long interviewId) {
        return interviewRepository.findByIdAndUserIdForUpdate(interviewId, userId)
                .orElseThrow(() -> new BusinessException(INTERVIEW_NOT_FOUND.getCode(), "面试不存在"));
    }

    /**
     * 面试结束时释放简历和岗位锁定。
     */
    private void unlockResumeAndPosition(Interview interview) {
        Resume resume = resumeRepository
                .findByIdAndUserIdForUpdate(interview.getResumeId(), interview.getUserId())
                .orElse(null);
        if (resume != null && interview.getId().equals(resume.getLockInterviewId())) {
            resume.setLockInterviewId(null);
            resumeRepository.save(resume);
        }
        Position position = positionRepository.findOwnedPersonalByIdForUpdate(
                        interview.getPositionId(), interview.getUserId())
                .orElse(null);
        if (position != null && interview.getId().equals(position.getLockInterviewId())) {
            position.setLockInterviewId(null);
            positionRepository.save(position);
        }
    }

    private void syncContext(InterviewContext context, Interview interview) {
        context.setCurrentTopicId(interview.getCurrentTopicId());
        context.setCurrentTopicName(interview.getCurrentTopicName());
        context.setCurrentDepth(interview.getCurrentDepth());
        context.setCurrentTopicFollowUpCount(interview.getCurrentTopicFollowUpCount());
        context.setConsecutiveFailures(interview.getConsecutiveFailures());
        context.setConsecutiveExcellence(interview.getConsecutiveExcellence());
        context.setTotalQuestionCount(interview.getTotalQuestionCount());
        context.setCurrentPhaseQuestionCount(interview.getCurrentPhaseQuestionCount());
        context.setSelfIntroQuestionCount(interview.getSelfIntroQuestionCount());
        context.setCurrentProjectIndex(interview.getCurrentProjectIndex());
        context.setCurrentBehavioralIndex(interview.getCurrentBehavioralIndex());
    }

    private List<InterviewPhase> parseAndSortPhases(List<String> phaseNames) {
        List<InterviewPhase> phases = new ArrayList<>();
        for (String name : phaseNames) {
            if (name == null || name.isBlank()) {
                throw new BusinessException(INVALID_PHASE.getCode(), "面试环节不能为空");
            }
            try {
                phases.add(InterviewPhase.valueOf(name.trim().toUpperCase()));
            } catch (IllegalArgumentException e) {
                throw new BusinessException(INVALID_PHASE.getCode(), "无效环节: " + name);
            }
        }
        phases = phases.stream().distinct().collect(Collectors.toList());
        phases.sort(java.util.Comparator.comparingInt(InterviewPhase::getOrder));
        // 去掉重复的 ENDING 由 InterviewPhase.sortSelected 处理，这里只做简单排序
        if (phases.isEmpty() || phases.get(phases.size() - 1) != InterviewPhase.ENDING) {
            phases.add(InterviewPhase.ENDING);
        }
        return phases;
    }

    private InterviewDetailResponse toDetailResponse(Interview interview) {
        InterviewDetailResponse response = new InterviewDetailResponse();
        response.setInterviewId(interview.getId());
        response.setResumeId(interview.getResumeId());
        response.setPositionId(interview.getPositionId());
        response.setStatus(interview.getStatus().name());
        response.setStatusLabel(interview.getStatus().getDisplayName());
        response.setCurrentPhase(interview.getCurrentPhase().name());
        response.setCurrentPhaseLabel(interview.getCurrentPhase().getDisplayName());
        response.setCurrentTopic(interview.getCurrentTopicName());
        response.setCurrentDepth(interview.getCurrentDepth());
        response.setTotalQuestionCount(interview.getTotalQuestionCount());
        List<String> selectedPhases = parsePhasesJson(interview.getSelectedPhases());
        response.setSelectedPhases(selectedPhases);
        response.setPhaseLabels(toPhaseLabels(selectedPhases));
        response.setPendingQuestion(InterviewTurnStateService.isTurnReservation(
                interview.getPendingQuestion()) ? null : interview.getPendingQuestion());
        response.setStartedAt(interview.getStartedAt());
        response.setEndedAt(interview.getEndedAt());

        // 对已结束或已中断的面试，基于候选人回答内容计算综合评分
        if (interview.getStatus() == InterviewStatus.ENDED
                || interview.getStatus() == InterviewStatus.INTERRUPTED) {
            List<InterviewMessage> candidateMessages = messageRepository
                    .findByInterviewIdAndRole(interview.getId(), "candidate");
            int totalScore = calculateOverallScore(candidateMessages);
            response.setOverallScore(totalScore);
            response.setGrade(calculateGrade(totalScore));
        }

        response.setPositionTitle(interview.getPositionNameSnapshot());
        response.setCompanyName(interview.getCompanyNameSnapshot());
        response.setJobCategory(interview.getJobCategorySnapshot());
        return response;
    }

    private InterviewMessageResponse toMessageResponse(InterviewMessage message) {
        InterviewMessageResponse response = new InterviewMessageResponse();
        response.setMessageId(message.getId());
        response.setPhase(message.getPhase());
        response.setPhaseLabel(InterviewPhase.displayNameOf(message.getPhase()));
        response.setRole(message.getRole());
        response.setContent(message.getContent());
        response.setTopic(message.getTopicName());
        response.setDepth(message.getDepth());
        response.setSeqNo(message.getSeqNo());
        response.setCreatedAt(message.getCreatedAt() != null
                ? message.getCreatedAt().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")) : null);
        return response;
    }

    private List<String> parsePhasesJson(String json) {
        if (json == null || json.isBlank()) {
            return Arrays.asList(InterviewPhase.SELF_INTRO.name(), InterviewPhase.PROFESSIONAL.name(),
                    InterviewPhase.RESUME_DISCUSSION.name(), InterviewPhase.BEHAVIORAL.name(), InterviewPhase.ENDING.name());
        }
        try {
            return objectMapper.readValue(json, objectMapper.getTypeFactory()
                    .constructCollectionType(List.class, String.class));
        } catch (JsonProcessingException e) {
            return new ArrayList<>();
        }
    }

    private Map<String, String> toPhaseLabels(List<String> phaseCodes) {
        Map<String, String> labels = new LinkedHashMap<>();
        for (String phaseCode : phaseCodes) {
            labels.put(phaseCode, InterviewPhase.displayNameOf(phaseCode));
        }
        return labels;
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            throw new BusinessException(InterviewErrorCode.INVALID_PHASE.getCode(), "序列化失败", e);
        }
    }

    private <T> T parseJson(String json, Class<T> clazz) {
        if (json == null || json.isBlank()) {
            try {
                return clazz.getDeclaredConstructor().newInstance();
            } catch (Exception e) {
                return null;
            }
        }
        String normalized = normalizeJson(json);
        try {
            return objectMapper.readValue(normalized, clazz);
        } catch (JsonProcessingException e) {
            log.warn("[InterviewService] JSON 解析失败: {}", e.getMessage());
            try {
                return clazz.getDeclaredConstructor().newInstance();
            } catch (Exception ex) {
                return null;
            }
        }
    }

    /**
     * 面试运行所需的正式快照缺失或损坏时显式失败，不能回查实时资源或生成空画像。
     */
    private <T> T requireSnapshotJson(String json, Class<T> clazz) {
        if (json == null || json.isBlank()) {
            throw new BusinessException(
                    INTERVIEW_SNAPSHOT_INVALID.getCode(), "面试快照数据异常");
        }
        try {
            T value = objectMapper.readValue(normalizeJson(json), clazz);
            if (value == null) {
                throw new BusinessException(
                        INTERVIEW_SNAPSHOT_INVALID.getCode(), "面试快照数据异常");
            }
            return value;
        } catch (JsonProcessingException e) {
            throw new BusinessException(
                    INTERVIEW_SNAPSHOT_INVALID.getCode(), "面试快照数据异常", e);
        }
    }

    private <T> T parseJson(String json, com.fasterxml.jackson.core.type.TypeReference<T> typeReference) {
        if (json == null || json.isBlank()) {
            return null;
        }
        String normalized = normalizeJson(json);
        try {
            return objectMapper.readValue(normalized, typeReference);
        } catch (JsonProcessingException e) {
            log.warn("[InterviewService] JSON 解析失败: {}", e.getMessage());
            return null;
        }
    }

    private String normalizeJson(String json) {
        String normalized = json.trim();
        if (normalized.startsWith("\"") && normalized.endsWith("\"")) {
            try {
                normalized = objectMapper.readValue(normalized, String.class);
            } catch (JsonProcessingException ignored) {
            }
        }
        return normalized;
    }

    /**
     * 基于候选人回答内容计算综合评分。
     */
    private int calculateOverallScore(List<InterviewMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return 60;
        }
        return Math.min(95, 60 + messages.stream()
                .filter(m -> "candidate".equals(m.getRole()))
                .mapToInt(m -> Math.min(m.getContent() == null ? 0 : m.getContent().length(), 200))
                .sum() / 50);
    }

    /**
     * 根据综合评分计算等级。
     */
    private String calculateGrade(int totalScore) {
        if (totalScore >= 90) {
            return "优秀";
        }
        if (totalScore >= 80) {
            return "良好";
        }
        if (totalScore >= 60) {
            return "合格";
        }
        return "待提升";
    }

    /**
     * 将报告实体转换为响应对象。
     */
    private InterviewReportResponse toReportResponse(InterviewReport entity) {
        InterviewReportResponse response = new InterviewReportResponse();
        response.setInterviewId(entity.getInterviewId());
        response.setOverallScore(entity.getOverallScore());
        response.setGrade(entity.getGrade());
        Map<String, InterviewReportResponse.PhaseSummary> phases = parseJson(entity.getPhases(),
                new com.fasterxml.jackson.core.type.TypeReference<LinkedHashMap<String, InterviewReportResponse.PhaseSummary>>() {
                });
        if (phases == null) {
            phases = new LinkedHashMap<>();
        }
        phases.replaceAll((phaseCode, summary) -> {
            InterviewReportResponse.PhaseSummary normalized = summary == null
                    ? new InterviewReportResponse.PhaseSummary() : summary;
            normalized.setPhaseLabel(InterviewPhase.displayNameOf(phaseCode));
            return normalized;
        });
        response.setPhases(phases);
        response.setDimensions(parseJson(entity.getDimensions(), InterviewReportResponse.DimensionScores.class));
        List<String> strengths = parseJson(entity.getStrengths(),
                new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {
                });
        response.setStrengths(strengths == null ? List.of() : strengths);
        List<String> weaknesses = parseJson(entity.getWeaknesses(),
                new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {
                });
        response.setWeaknesses(weaknesses == null ? List.of() : weaknesses);
        List<String> keyEvents = parseJson(entity.getKeyEvents(),
                new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {
                });
        response.setKeyEvents(keyEvents == null ? List.of() : keyEvents);
        response.setConclusion(entity.getConclusion());
        response.setMdContent(buildReportMarkdown(response));
        return response;
    }

    /**
     * 将报告响应对象转换为待保存实体。
     */
    private InterviewReport toReportEntity(Long userId, Long interviewId, InterviewReportResponse report) {
        InterviewReport entity = new InterviewReport();
        entity.setUserId(userId);
        entity.setInterviewId(interviewId);
        entity.setOverallScore(report.getOverallScore());
        entity.setGrade(report.getGrade());
        entity.setPhases(toJson(report.getPhases()));
        entity.setDimensions(toJson(report.getDimensions()));
        entity.setStrengths(toJson(report.getStrengths()));
        entity.setWeaknesses(toJson(report.getWeaknesses()));
        entity.setKeyEvents(toJson(report.getKeyEvents()));
        entity.setConclusion(report.getConclusion());
        entity.setMdContent(report.getMdContent());
        return entity;
    }

}
