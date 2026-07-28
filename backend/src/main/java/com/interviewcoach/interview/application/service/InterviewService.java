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
import com.interviewcoach.position.domain.entity.PositionAuditStatus;
import com.interviewcoach.position.domain.entity.PositionParseStatus;
import com.interviewcoach.position.domain.model.PositionProfileData;
import com.interviewcoach.position.domain.repository.PositionProfileRepository;
import com.interviewcoach.position.domain.repository.PositionRepository;
import com.interviewcoach.resume.domain.entity.Resume;
import com.interviewcoach.resume.domain.entity.ResumeParseStatus;
import com.interviewcoach.resume.domain.entity.ResumeProfile;
import com.interviewcoach.resume.domain.model.UserProfileData;
import com.interviewcoach.resume.domain.repository.ResumeProfileRepository;
import com.interviewcoach.resume.domain.repository.ResumeRepository;
import java.time.LocalDateTime;
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
    private final ResumeProfileRepository resumeProfileRepository;
    private final PositionRepository positionRepository;
    private final PositionProfileRepository positionProfileRepository;
    private final ReportAgent reportAgent;
    private final CoordinatorAgent coordinatorAgent;
    private final ReportDesensitizer reportDesensitizer;
    private final ObjectMapper objectMapper;

    /**
     * 创建面试。
     */
    @Transactional
    public CreateInterviewResponse createInterview(Long userId, CreateInterviewRequest request) {
        if (request.getSelectedPhases() == null || request.getSelectedPhases().isEmpty()) {
            throw new BusinessException(NO_PHASE_SELECTED.getCode(), "未选择任何环节");
        }

        List<InterviewPhase> selectedPhases = parseAndSortPhases(request.getSelectedPhases());

        Resume resume = resumeRepository.findByIdAndUserId(request.getResumeId(), userId)
                .orElseThrow(() -> new BusinessException(RESUME_NOT_FOUND.getCode(), "简历不存在"));
        if (resume.getParseStatus() != ResumeParseStatus.CONFIRMED) {
            throw new BusinessException(RESUME_STATUS_INVALID.getCode(), "简历未确认");
        }
        if (resume.isLocked() && !isInterviewActive(resume.getLockInterviewId())) {
            resume.setLockInterviewId(null);
        }
        if (resume.isLocked()) {
            throw new BusinessException(RESUME_LOCKED.getCode(), "简历已锁定在其他面试");
        }

        Position position = positionRepository.findByIdAndUserId(request.getPositionId(), userId)
                .orElseThrow(() -> new BusinessException(POSITION_NOT_FOUND.getCode(), "岗位不存在"));
        // MVP 阶段：岗位画像已确认即可用于面试，无需独立管理员审核
        if (position.getParseStatus() != PositionParseStatus.CONFIRMED) {
            throw new BusinessException(POSITION_STATUS_INVALID.getCode(), "岗位未就绪");
        }
        if (position.isLocked() && !isInterviewActive(position.getLockInterviewId())) {
            position.setLockInterviewId(null);
        }
        if (position.isLocked()) {
            throw new BusinessException(POSITION_LOCKED.getCode(), "岗位已锁定在其他面试");
        }

        UserProfileData userProfile = loadUserProfile(resume.getId());
        PositionProfileData positionProfile = loadPositionProfile(position.getId());

        Interview interview = new Interview();
        interview.setUserId(userId);
        interview.setResumeId(resume.getId());
        interview.setPositionId(position.getId());
        interview.setUserProfileSnapshot(toJson(userProfile));
        interview.setPositionProfileSnapshot(toJson(positionProfile));
        interview.setSelectedPhases(toJson(selectedPhases.stream().map(InterviewPhase::name).toList()));
        interview.setCurrentPhase(selectedPhases.get(0));
        interview.setStatus(InterviewStatus.IN_PROGRESS);
        interview.setTotalQuestionCount(0);
        interview.setCurrentPhaseQuestionCount(0);
        interviewRepository.save(interview);

        // 锁定简历和岗位
        resume.setLockInterviewId(interview.getId());
        resumeRepository.save(resume);
        position.setLockInterviewId(interview.getId());
        positionRepository.save(position);

        InterviewContext context = coordinatorAgent.initialize(interview, userProfile, positionProfile);
        String firstQuestion = coordinatorAgent.generateFirstQuestion(context);

        // 保存首题
        InterviewMessage firstMessage = new InterviewMessage();
        firstMessage.setInterviewId(interview.getId());
        firstMessage.setPhase(interview.getCurrentPhase().name());
        firstMessage.setRole("interviewer");
        firstMessage.setContent(firstQuestion);
        firstMessage.setTopicId(context.getCurrentTopicId());
        firstMessage.setTopicName(context.getCurrentTopicName());
        firstMessage.setDepth(context.getCurrentDepth());
        firstMessage.setSeqNo(1);
        messageRepository.save(firstMessage);
        interview.setTotalQuestionCount(1);
        interviewRepository.save(interview);

        CreateInterviewResponse response = new CreateInterviewResponse();
        response.setInterviewId(interview.getId());
        response.setStatus(interview.getStatus().name());
        response.setSelectedPhases(selectedPhases.stream().map(InterviewPhase::name).toList());
        response.setCurrentPhase(interview.getCurrentPhase().name());
        response.setFirstQuestion(firstQuestion);
        response.setPhaseOrder(selectedPhases.stream().map(InterviewPhase::name).toList());
        return response;
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
     * 处理回答并返回下一题（非流式内部方法，由 Controller 包装 SSE）。
     */
    @Transactional
    public TurnResult submitAnswer(Long userId, Long interviewId, String answer) {
        if (answer == null || answer.isBlank()) {
            throw new BusinessException(ANSWER_INVALID.getCode(), "回答内容无效");
        }
        if (answer.length() > 5000) {
            throw new BusinessException(ANSWER_INVALID.getCode(), "回答内容过长");
        }

        Interview interview = findInterview(userId, interviewId);
        if (interview.getStatus() == InterviewStatus.ENDED) {
            throw new BusinessException(INTERVIEW_ENDED.getCode(), "面试已结束");
        }
        if (interview.getStatus() == InterviewStatus.INTERRUPTED) {
            throw new BusinessException(INTERVIEW_INTERRUPTED.getCode(), "面试已中断");
        }

        UserProfileData userProfile = parseJson(interview.getUserProfileSnapshot(), UserProfileData.class);
        PositionProfileData positionProfile = parseJson(interview.getPositionProfileSnapshot(), PositionProfileData.class);
        InterviewContext context = coordinatorAgent.initialize(interview, userProfile, positionProfile);
        syncContext(context, interview);

        // 获取上一轮问题
        List<InterviewMessage> messages = messageRepository.findByInterviewIdOrderBySeqNoAsc(interviewId);
        String lastQuestion = messages.stream()
                .filter(m -> "interviewer".equals(m.getRole()))
                .reduce((a, b) -> b)
                .map(InterviewMessage::getContent)
                .orElse("请简要介绍一下自己。");

        TurnResult result = coordinatorAgent.coordinate(context, interview, lastQuestion, answer);

        // 同步回实体
        interview.setCurrentPhase(result.getPhase());
        interview.setCurrentTopicId(result.getTopicId());
        interview.setCurrentTopicName(result.getTopicName());
        interview.setCurrentDepth(result.getDepth());
        interview.setCurrentTopicFollowUpCount(context.getCurrentTopicFollowUpCount());
        interview.setConsecutiveFailures(context.getConsecutiveFailures());
        interview.setConsecutiveExcellence(context.getConsecutiveExcellence());
        interview.setLastEvaluationSeq(context.getLastEvaluationSeq());
        // 每个 turn 产生一条回答和一道新问题，总消息数需要加 2
        interview.setTotalQuestionCount(interview.getTotalQuestionCount() + 2);
        interview.setCurrentPhaseQuestionCount(context.getCurrentPhaseQuestionCount());
        interview.setSelfIntroQuestionCount(context.getSelfIntroQuestionCount());
        interview.setCurrentProjectIndex(context.getCurrentProjectIndex());
        interview.setCurrentBehavioralIndex(context.getCurrentBehavioralIndex());
        if (result.getPreviousPhase() != null) {
            interview.setCurrentPhaseQuestionCount(0);
        }
        if (result.getPhase() == InterviewPhase.ENDING) {
            interview.setStatus(InterviewStatus.ENDED);
            interview.setEndedAt(LocalDateTime.now());
        }
        interviewRepository.save(interview);

        return result;
    }

    /**
     * 主动结束面试。
     */
    @Transactional
    public InterviewDetailResponse endInterview(Long userId, Long interviewId) {
        Interview interview = findInterview(userId, interviewId);
        coordinatorAgent.endInterview(interview, true);
        interviewRepository.save(interview);
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
        PositionProfileData positionProfile = loadPositionProfile(interview.getPositionId());

        InterviewReportResponse report = new InterviewReportResponse();
        report.setInterviewId(interviewId);

        int totalScore = calculateOverallScore(messages);
        report.setOverallScore(totalScore);
        report.setGrade(calculateGrade(totalScore));

        Map<String, InterviewReportResponse.PhaseSummary> phases = new LinkedHashMap<>();
        for (InterviewPhase phase : InterviewPhase.values()) {
            long count = messages.stream().filter(m -> m.getPhase().equals(phase.name()) && "interviewer".equals(m.getRole())).count();
            InterviewReportResponse.PhaseSummary summary = new InterviewReportResponse.PhaseSummary();
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
        Position position = positionRepository.findById(interview.getPositionId()).orElse(null);
        String companyName = position != null ? position.getCompanyName() : null;
        String candidateName = extractCandidateName(interview.getUserProfileSnapshot());
        InterviewReportResponse desensitized = reportDesensitizer.desensitize(report, companyName, candidateName);

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
            md.append("| ").append(entry.getKey()).append(" | ")
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
     * 判断指定面试是否仍在进行中。
     */
    private boolean isInterviewActive(Long interviewId) {
        if (interviewId == null) {
            return false;
        }
        return interviewRepository.findById(interviewId)
                .map(i -> i.getStatus() == InterviewStatus.IN_PROGRESS)
                .orElse(false);
    }

    /**
     * 面试结束时释放简历和岗位锁定。
     */
    private void unlockResumeAndPosition(Interview interview) {
        Resume resume = resumeRepository.findById(interview.getResumeId()).orElse(null);
        if (resume != null && interview.getId().equals(resume.getLockInterviewId())) {
            resume.setLockInterviewId(null);
            resumeRepository.save(resume);
        }
        Position position = positionRepository.findById(interview.getPositionId()).orElse(null);
        if (position != null && interview.getId().equals(position.getLockInterviewId())) {
            position.setLockInterviewId(null);
            positionRepository.save(position);
        }
    }

    private UserProfileData loadUserProfile(Long resumeId) {
        ResumeProfile profile = resumeProfileRepository.findByResumeId(resumeId)
                .orElseThrow(() -> new BusinessException(RESUME_NOT_FOUND.getCode(), "简历画像不存在"));
        return parseJson(profile.getProfileData(), UserProfileData.class);
    }

    private PositionProfileData loadPositionProfile(Long positionId) {
        return positionProfileRepository.findByPositionId(positionId)
                .map(p -> parseJson(p.getProfileData(), PositionProfileData.class))
                .orElseGet(PositionProfileData::empty);
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
            try {
                phases.add(InterviewPhase.valueOf(name.toUpperCase()));
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
        response.setCurrentPhase(interview.getCurrentPhase().name());
        response.setCurrentTopic(interview.getCurrentTopicName());
        response.setCurrentDepth(interview.getCurrentDepth());
        response.setTotalQuestionCount(interview.getTotalQuestionCount());
        response.setSelectedPhases(parsePhasesJson(interview.getSelectedPhases()));
        response.setPendingQuestion(interview.getPendingQuestion());
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

        Position position = positionRepository.findById(interview.getPositionId()).orElse(null);
        if (position != null) {
            response.setPositionTitle(position.getPositionName());
            response.setCompanyName(position.getCompanyName());
        }
        return response;
    }

    private InterviewMessageResponse toMessageResponse(InterviewMessage message) {
        InterviewMessageResponse response = new InterviewMessageResponse();
        response.setMessageId(message.getId());
        response.setPhase(message.getPhase());
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
        response.setPhases(parseJson(entity.getPhases(),
                new com.fasterxml.jackson.core.type.TypeReference<LinkedHashMap<String, InterviewReportResponse.PhaseSummary>>() {
                }));
        response.setDimensions(parseJson(entity.getDimensions(), InterviewReportResponse.DimensionScores.class));
        response.setStrengths(parseJson(entity.getStrengths(),
                new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {
                }));
        response.setWeaknesses(parseJson(entity.getWeaknesses(),
                new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {
                }));
        response.setKeyEvents(parseJson(entity.getKeyEvents(),
                new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {
                }));
        response.setConclusion(entity.getConclusion());
        response.setMdContent(entity.getMdContent());
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

    /**
     * 从用户画像快照中提取候选人姓名。
     */
    private String extractCandidateName(String userProfileSnapshot) {
        UserProfileData profile = parseJson(userProfileSnapshot, UserProfileData.class);
        if (profile == null || profile.getBasicInfo() == null) {
            return null;
        }
        return profile.getBasicInfo().getName();
    }
}
