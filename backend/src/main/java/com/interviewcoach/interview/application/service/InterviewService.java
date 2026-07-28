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
 * 面试核心流程的应用服务。
 *
 * <p>上游由 {@code InterviewController} 调用。本服务负责校验当前用户的简历和岗位、保存面试状态与画像快照、
 * 管理资源占用，并把每轮问答交给 {@code CoordinatorAgent}。协调器会保存候选人回答和下一题，
 * 本服务再把协调结果中的环节、主题和计数同步回面试记录；报告流程则负责复用已有报告或生成、脱敏后保存。</p>
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
     * 创建一场进行中的面试并返回首题。
     *
     * <p>先校验所选环节及当前用户已确认的简历、岗位，并清理指向非进行中面试的残留占用；
     * 再读取双方画像，把画像和环节顺序作为本场快照保存，随后标记简历、岗位由本场面试占用。
     * 最后由协调器按首个环节生成开场题，本服务保存首题并返回面试编号、状态、环节顺序和题目。
     * 使用快照是为了让本场面试不受之后画像修改的影响。</p>
     *
     * <p>资源占用采用“先查询、后写入”，当前没有数据库锁或原子条件更新；并发创建时，
     * 多个请求仍可能同时通过未占用检查。</p>
     */
    @Transactional
    public CreateInterviewResponse createInterview(Long userId, CreateInterviewRequest request) {
        // 1. 校验环节，并加载当前用户已确认且未被有效面试占用的简历和岗位。
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

        // 2. 读取已确认画像，后续以快照形式固定为本场面试的上下文。
        UserProfileData userProfile = loadUserProfile(resume.getId());
        PositionProfileData positionProfile = loadPositionProfile(position.getId());

        // 3. 保存 IN_PROGRESS 面试，并固化画像与环节顺序。
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

        // 4. 将简历和岗位标记为由本场面试占用；这一步与前面的未占用检查不是原子操作。
        resume.setLockInterviewId(interview.getId());
        resumeRepository.save(resume);
        position.setLockInterviewId(interview.getId());
        positionRepository.save(position);

        // 5. 协调器按首个环节生成开场题，本服务负责保存首题并计入消息总数。
        InterviewContext context = coordinatorAgent.initialize(interview, userProfile, positionProfile);
        String firstQuestion = coordinatorAgent.generateFirstQuestion(context);

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
     * 获取当前用户的面试详情。
     *
     * <p>先按面试 ID 和用户 ID 校验资源归属，再组装响应。组装时每次都会查询岗位信息；
     * 面试已结束或已中断时，还会查询候选人回答并按当前本地规则重新计算分数和等级。</p>
     */
    @Transactional(readOnly = true)
    public InterviewDetailResponse getInterview(Long userId, Long interviewId) {
        Interview interview = findInterview(userId, interviewId);
        return toDetailResponse(interview);
    }

    /**
     * 同步处理一轮回答并返回下一题。
     *
     * <p>先校验回答和面试状态，再用创建时保存的画像快照及持久化进度恢复上下文，并取最近一道面试官问题。
     * {@code CoordinatorAgent} 会在内部保存本轮回答、执行 Skill 评估与决策，并保存生成的下一题；
     * 本服务只把协调器修改后的环节、主题和计数同步回面试记录，再把结果交给 Controller 包装成 SSE 事件。
     * 当协调结果进入 {@code ENDING} 时会把面试标记为已结束，但当前路径不会立即释放简历和岗位占用。</p>
     *
     * <p>本方法和协调器保存回答、保存下一题的操作处于同一事务。需要评估时，模型调用也在当前线程和事务中完成；
     * 任一步骤抛出异常，本轮回答、下一题和面试进度会一起回滚，等待模型期间事务不会提前结束。</p>
     */
    @Transactional
    public TurnResult submitAnswer(Long userId, Long interviewId, String answer) {
        // 1. 校验回答内容，并确认面试仍允许继续提交。
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

        // 2. 从本场快照和已保存进度恢复协调器上下文，避免后续画像修改影响当前面试。
        UserProfileData userProfile = parseJson(interview.getUserProfileSnapshot(), UserProfileData.class);
        PositionProfileData positionProfile = parseJson(interview.getPositionProfileSnapshot(), PositionProfileData.class);
        InterviewContext context = coordinatorAgent.initialize(interview, userProfile, positionProfile);
        syncContext(context, interview);

        // 3. 找到最近一道面试官问题，为本轮“问题 + 回答”评估提供完整输入。
        List<InterviewMessage> messages = messageRepository.findByInterviewIdOrderBySeqNoAsc(interviewId);
        String lastQuestion = messages.stream()
                .filter(m -> "interviewer".equals(m.getRole()))
                .reduce((a, b) -> b)
                .map(InterviewMessage::getContent)
                .orElse("请简要介绍一下自己。");

        // 4. 协调器会保存候选人回答和下一题，并返回新的环节、主题、深度与评估信号。
        TurnResult result = coordinatorAgent.coordinate(context, interview, lastQuestion, answer);

        // 5. 将协调器上下文同步回面试；自动进入 ENDING 时只改结束状态，不在此处释放资源占用。
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
     * 主动中断面试并释放本场占用的简历和岗位。
     *
     * <p>先确认面试属于当前用户，再由协调器把状态改为 {@code INTERRUPTED} 并记录结束时间，
     * 保存后仅清除仍指向本场面试的资源占用，最后返回更新后的详情。
     * 这与回答流程自动进入 {@code ENDING} 不同：当前只有主动结束路径会立即执行释放。</p>
     *
     * <p>当前没有校验面试原状态；即使面试已经自然结束或已经中断，再次调用仍会把状态写为
     * {@code INTERRUPTED}，并重新记录结束时间。</p>
     */
    @Transactional
    public InterviewDetailResponse endInterview(Long userId, Long interviewId) {
        // 1. 加载当前用户的面试，并将其标记为主动中断。
        Interview interview = findInterview(userId, interviewId);
        coordinatorAgent.endInterview(interview, true);
        // 2. 先保存中断状态，再释放仍由本场面试持有的资源占用。
        interviewRepository.save(interview);
        unlockResumeAndPosition(interview);
        // 3. 返回包含最新状态和结束时间的面试详情。
        return toDetailResponse(interview);
    }

    /**
     * 查询当前用户的面试列表并逐条组装详情。
     *
     * <p>每条面试都会额外查询岗位信息；已经结束或中断的面试还会查询候选人回答，
     * 按当前本地规则重新计算分数和等级。</p>
     */
    @Transactional(readOnly = true)
    public List<InterviewDetailResponse> listInterviews(Long userId) {
        return interviewRepository.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(this::toDetailResponse)
                .toList();
    }

    /**
     * 获取当前用户指定面试的消息列表。
     *
     * <p>先按面试 ID 和用户 ID 校验资源归属，再按消息序号升序返回整场问答。</p>
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
     * <p>优先返回数据库中已保存的报告；没有报告时，读取完整问答和岗位画像，计算基础评分，
     * 再由 {@code ReportAgent} 分析优劣势。报告必须先完成公司和候选人信息脱敏，再保存并返回。
     * 当前采用“先查询、后生成保存”，没有并发互斥，因此并发首次请求仍可能重复生成报告。</p>
     *
     * <p>当前不限制面试状态，进行中的面试也可以生成并保存报告。基础分由候选人回答长度计算，
     * 各维度分数由基础分减固定差值产生，不是五次独立评估；模型分析失败时，
     * {@code ReportAgent} 会改用本地规则生成优劣势。</p>
     *
     * <p>首次生成时，模型分析、脱敏、序列化和保存都在本事务中同步执行；后续步骤抛出异常时，
     * 本次新报告不会提交。已保存报告的结构化字段解析失败时不会重新生成，而会按解析方法的兜底结果返回。</p>
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

        // 2. 根据完整问答计算基础分和环节完成情况，再补充针对岗位的内容分析。
        List<InterviewMessage> messages = messageRepository.findByInterviewIdOrderBySeqNoAsc(interviewId);
        PositionProfileData positionProfile = loadPositionProfile(interview.getPositionId());

        InterviewReportResponse report = new InterviewReportResponse();
        report.setInterviewId(interviewId);

        int totalScore = calculateOverallScore(messages);
        report.setOverallScore(totalScore);
        report.setGrade(calculateGrade(totalScore));

        Map<String, InterviewReportResponse.PhaseSummary> phases = new LinkedHashMap<>();
        // 题目数只统计面试官消息；完成状态按当前环节顺序判断，ENDING 当前固定记为已完成。
        for (InterviewPhase phase : InterviewPhase.values()) {
            long count = messages.stream().filter(m -> m.getPhase().equals(phase.name()) && "interviewer".equals(m.getRole())).count();
            InterviewReportResponse.PhaseSummary summary = new InterviewReportResponse.PhaseSummary();
            summary.setQuestionCount((int) count);
            summary.setCompleted(phase == InterviewPhase.ENDING || interview.getCurrentPhase().getOrder() > phase.getOrder());
            phases.put(phase.name(), summary);
        }
        report.setPhases(phases);

        // 当前没有分别计算五个维度，均由综合分减去固定差值产生。
        InterviewReportResponse.DimensionScores dimensions = new InterviewReportResponse.DimensionScores();
        dimensions.setTechnicalDepth(totalScore);
        dimensions.setTechnicalBreadth(totalScore - 3);
        dimensions.setPracticalExperience(totalScore - 1);
        dimensions.setExpression(totalScore - 2);
        dimensions.setLearningAbility(totalScore - 4);
        report.setDimensions(dimensions);

        // Agent 优先用模型分析真实问答，模型异常时内部改用本地规则；合法结果为空时由此处补默认文案。
        ReportAgent.AnalysisResult analysis = reportAgent.analyze(messages, positionProfile);
        report.setStrengths(analysis.strengths().isEmpty()
                ? List.of("参与面试态度积极") : analysis.strengths());
        report.setWeaknesses(analysis.weaknesses().isEmpty()
                ? List.of("建议补充更多实践案例和原理细节") : analysis.weaknesses());
        report.setKeyEvents(List.of());
        report.setConclusion("本次面试已完成，候选人整体表现" + report.getGrade()
                + "。" + String.join("；", analysis.weaknesses()));
        report.setMdContent(buildReportMarkdown(report));

        // 3. 在持久化之前隐去公司信息和候选人姓名，避免数据库保存未脱敏报告。
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

    /**
     * 按面试 ID 和用户 ID 查询面试，同时完成资源归属校验。
     *
     * <p>详情、回答、结束、报告和消息接口都通过该方法限制只能访问当前用户的面试。
     * 记录不存在或属于其他用户时统一按“面试不存在”处理，不向调用方暴露他人资源是否存在。</p>
     */
    private Interview findInterview(Long userId, Long interviewId) {
        return interviewRepository.findByIdAndUserId(interviewId, userId)
                .orElseThrow(() -> new BusinessException(INTERVIEW_NOT_FOUND.getCode(), "面试不存在"));
    }

    /**
     * 判断资源占用所指向的面试是否仍然有效。
     *
     * <p>创建面试时用该结果决定是否清理简历或岗位上的旧占用。面试 ID 为空、记录不存在，
     * 或状态不是 {@code IN_PROGRESS} 时都返回 {@code false}。</p>
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
     * 释放当前面试持有的简历和岗位占用。
     *
     * <p>只有资源仍指向当前面试时才清除，避免误删其他面试后来建立的占用。
     * 当前该方法只由主动结束流程调用，回答流程自动进入收尾时不会调用。</p>
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

    /**
     * 读取创建面试所需的简历画像，并把已保存的 JSON 还原为业务对象。
     *
     * <p>简历画像记录不存在时会直接报错并停止创建面试；画像内容无法解析时，按 {@link #parseJson}
     * 的现有规则返回默认对象。</p>
     */
    private UserProfileData loadUserProfile(Long resumeId) {
        ResumeProfile profile = resumeProfileRepository.findByResumeId(resumeId)
                .orElseThrow(() -> new BusinessException(RESUME_NOT_FOUND.getCode(), "简历画像不存在"));
        return parseJson(profile.getProfileData(), UserProfileData.class);
    }

    /**
     * 读取岗位画像，供创建面试时保存岗位快照，以及生成报告时判断岗位要求。
     *
     * <p>查询到画像记录时，会把其中保存的 JSON 还原为业务对象；没有画像记录时不会报错，
     * 而是返回空画像让当前流程继续。画像内容无法解析时，按 {@link #parseJson} 的现有规则返回默认对象。</p>
     */
    private PositionProfileData loadPositionProfile(Long positionId) {
        return positionProfileRepository.findByPositionId(positionId)
                .map(p -> parseJson(p.getProfileData(), PositionProfileData.class))
                .orElseGet(PositionProfileData::empty);
    }

    /**
     * 用面试实体中已保存的进度恢复协调器上下文。
     *
     * <p>同步方向是从 {@link Interview} 到 {@link InterviewContext}：把主题、深度和各类计数覆盖到
     * 刚初始化的上下文，供下一轮协调继续使用。该方法只恢复内存状态，不负责保存数据库。</p>
     */
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

    /**
     * 把请求中的环节名称整理为本场面试的固定执行顺序。
     *
     * <p>名称不区分大小写，无法转换为枚举时直接报错；有效环节先去重，再按枚举定义的顺序排列。
     * 请求没有包含 {@code ENDING} 时会强制追加，作为固定收尾环节。</p>
     */
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
        if (phases.isEmpty() || phases.get(phases.size() - 1) != InterviewPhase.ENDING) {
            phases.add(InterviewPhase.ENDING);
        }
        return phases;
    }

    /**
     * 组装面试详情，并补充需要额外查询的动态信息。
     *
     * <p>面试已结束或已中断时，会查询候选人回答并实时计算分数；无论状态如何，都会查询岗位以补充名称和公司。
     * 因此列表接口逐条调用本方法时，也会为每条记录执行这些额外查询。</p>
     */
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

    /**
     * 还原面试保存的环节顺序。
     *
     * <p>字段为空时返回完整的默认环节；字段存在但 JSON 无法解析时静默返回空列表，
     * 当前不会记录警告，也不会恢复为默认环节。</p>
     */
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

    /**
     * 把快照、环节或报告结构序列化为 JSON。
     *
     * <p>任意对象序列化失败都会转为业务异常；当前所有调用场景统一使用 {@code INVALID_PHASE} 错误码，
     * 即使失败内容不是环节数据。</p>
     */
    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            throw new BusinessException(InterviewErrorCode.INVALID_PHASE.getCode(), "序列化失败", e);
        }
    }

    /**
     * 把 JSON 还原为指定对象，并在数据不可用时尽量返回默认实例。
     *
     * <p>解析前会兼容解开一层“字符串形式的 JSON”。字段为空或解析失败时，尝试调用无参构造方法创建对象；
     * 无法创建时才返回 {@code null}。解析失败只记录警告，不会阻止上层流程继续。</p>
     */
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
     * 把 JSON 还原为列表、映射等带泛型的结构。
     *
     * <p>同样会先兼容解开一层“字符串形式的 JSON”，但与对象重载不同：字段为空或解析失败时直接返回
     * {@code null}，不会创建空集合；解析失败会记录警告。</p>
     */
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

    /**
     * 兼容数据库中被额外包成 JSON 字符串的历史内容。
     *
     * <p>文本首尾都是引号时尝试解开一层；解包失败会忽略异常并保留原文本，交给后续正式解析处理。</p>
     */
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
     * 基于候选人回答长度计算综合评分。
     *
     * <p>基础分为 60，只统计角色为 {@code candidate} 的消息；每条回答最多计入 200 个字符，
     * 累计字符数每满 50 增加 1 分，最高 95 分。没有消息或没有候选人回答时均为 60 分，
     * 当前不会使用每轮评估信号参与计算。</p>
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
     * 将已保存的报告实体还原为响应对象。
     *
     * <p>结构化字段分别从 JSON 解析。列表或映射字段为空、损坏时会变成 {@code null}，
     * 普通对象字段会按对象解析方法返回默认实例或 {@code null}；当前不会因为这些字段损坏而重新生成报告。</p>
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
     * 从用户画像快照中提取候选人姓名，供报告脱敏使用。
     *
     * <p>快照无法解析或没有基本信息时返回 {@code null}；此时脱敏器仍会处理其他信息，
     * 但无法按候选人姓名执行文本替换。</p>
     */
    private String extractCandidateName(String userProfileSnapshot) {
        UserProfileData profile = parseJson(userProfileSnapshot, UserProfileData.class);
        if (profile == null || profile.getBasicInfo() == null) {
            return null;
        }
        return profile.getBasicInfo().getName();
    }
}
