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
import com.interviewcoach.interview.infrastructure.redis.InterviewSlotManager;
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
 * 面试 HTTP 用例的应用编排服务。
 *
 * <p>它把用户归属查询、短事务状态服务、并发槽位、Coordinator/报告 Agent、报告有限字段
 * 替换和 DTO 映射串成创建、回答、结束、查询及报告流程。LLM 调用位于短事务之外；会话、
 * 消息、快照和报告由仓储持久化，接口层只负责取得安全上下文中的当前用户 ID。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InterviewService {

    /** 负责按用户查询或加锁读取面试，并保存主动结束状态。 */
    private final InterviewRepository interviewRepository;

    /** 负责读取完整消息历史和报告评分所需的候选人消息。 */
    private final InterviewMessageRepository messageRepository;

    /** 负责读取已生成报告及保存首次生成结果。 */
    private final InterviewReportRepository reportRepository;

    /** 负责主动结束时精确释放当前用户简历锁。 */
    private final ResumeRepository resumeRepository;

    /** 负责主动结束时精确释放当前用户私有岗位锁。 */
    private final PositionRepository positionRepository;

    /** 负责创建快照、首题落库及初始化失败补偿的短事务。 */
    private final InterviewCreationStateService creationStateService;

    /** 负责轮次 reservation、消息双写、上下文写回和失败释放的短事务。 */
    private final InterviewTurnStateService turnStateService;

    /** 负责根据已保存问答和岗位画像生成报告中的优势、薄弱点与结论素材。 */
    private final ReportAgent reportAgent;

    /** 负责初始化运行上下文、生成首题并按确定性 Skill 流程推进每一轮。 */
    private final CoordinatorAgent coordinatorAgent;

    /** 负责对报告中的有限字段执行精确文本替换。 */
    private final ReportDesensitizer reportDesensitizer;

    /** 负责用 Redis 占用和释放进行中面试的全局并发槽位。 */
    private final InterviewSlotManager interviewSlotManager;

    /** 负责会话快照、环节列表和报告 TEXT 字段的 JSON 转换。 */
    private final ObjectMapper objectMapper;

    /**
     * 使用当前正式事实画像创建面试并固定快照；当前可用的 AI 分析一并快照，缺失时不阻断。
     *
     * <p>本方法会整理环节、在短事务内准备会话、占用 Redis 槽位、在事务外生成首题，再用
     * 第二个短事务写回。槽位不足或后续初始化失败时，会把准备记录标记为中断并释放对应锁。</p>
     */
    public CreateInterviewResponse createInterview(Long userId, CreateInterviewRequest request) {
        if (request.getSelectedPhases() == null || request.getSelectedPhases().isEmpty()) {
            throw new BusinessException(NO_PHASE_SELECTED.getCode(), "未选择任何环节");
        }

        // 将 HTTP 中的环节名转换为稳定枚举顺序，去重并保证结束环节位于末尾。
        List<InterviewPhase> selectedPhases = parseAndSortPhases(request.getSelectedPhases());

        // 在短事务内复核资源归属和画像、固化快照并锁定简历及私有岗位。
        PreparedInterview prepared = creationStateService.prepare(
                userId,
                request.getResumeId(),
                request.getPositionId(),
                selectedPhases);
        Interview interview = prepared.interview();

        // 占用 Redis 并发槽位；达到上限时不排队，立即中断准备记录并向用户返回繁忙。
        if (!interviewSlotManager.acquireSlot(interview.getId())) {
            BusinessException busyException = new BusinessException(
                    INTERVIEW_SERVER_BUSY.getCode(), "当前面试者过多，请稍后尝试");
            compensateFailedCreation(userId, interview.getId(), busyException);
            throw busyException;
        }

        String firstQuestion;
        try {
            // 用本场快照建立可信运行上下文，再由当前环节 Skill 选择题库、LLM 或固定模板首题。
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
            // 首题生成成功后，在独立短事务中原子保存首题和 Coordinator 初始化后的上下文。
            interview = creationStateService.completeFirstQuestion(
                    userId, interview.getId(), firstQuestion, context);
        } catch (RuntimeException e) {
            // 初始化未完成时先释放槽位，再尽力中断准备记录；原始异常仍作为对外失败。
            interviewSlotManager.releaseSlot(interview.getId());
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
     * 补偿负责把仍进行中的准备记录置为中断，并精确释放简历和私有岗位锁。
     */
    private void compensateFailedCreation(
            Long userId, Long interviewId, RuntimeException originalFailure) {
        try {
            // 通过另一个 Spring Bean 进入事务代理，避免补偿参加已失败的初始化调用栈。
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

    /** 按当前用户归属读取会话快照并转换详情；查询本身不生成报告或推进面试。 */
    @Transactional(readOnly = true)
    public InterviewDetailResponse getInterview(Long userId, Long interviewId) {
        // 仓储查询同时限制面试 ID 和当前用户，越权与不存在统一返回相同业务错误。
        Interview interview = findInterview(userId, interviewId);
        return toDetailResponse(interview);
    }

    /**
     * 使用创建面试时固定的事实和可选分析快照处理一轮回答。
     *
     * <p>5000 是当前代码固定的 UTF-16 字符单元长度上限，精确产品依据缺失。通过校验后先
     * 写 reservation，再在事务外解析快照和执行 Coordinator，最后原子写入回答与下一题。
     * 本方法同步返回结果，由 Controller 按 SSE 事件顺序发送，不在此处建立异步任务。</p>
     */
    public TurnResult submitAnswer(Long userId, Long interviewId, String answer) {
        if (answer == null || answer.isBlank()) {
            throw new BusinessException(ANSWER_INVALID.getCode(), "回答内容无效");
        }
        if (answer.length() > 5000) {
            throw new BusinessException(ANSWER_INVALID.getCode(), "回答内容过长");
        }

        // 短事务写入一次性 reservation，并固化本轮问题及旧环节状态，拒绝重复提交。
        ReservedTurn reserved = turnStateService.reserve(userId, interviewId);
        try {
            Interview interview = reserved.interview();
            // 两份正式快照必须可解析；缺失或坏 JSON 显式失败，不回查可能已变化的实时画像。
            UserProfileData userProfile = requireSnapshotJson(
                    interview.getUserProfileSnapshot(), UserProfileData.class);
            // 可选辅助分析损坏时沿用兼容行为降级为空对象或 null，不阻断正式事实面试。
            ResumeProfileAnalysisData userProfileAnalysis = parseJson(
                    interview.getUserProfileAnalysisSnapshot(), ResumeProfileAnalysisData.class);
            PositionProfileData positionProfile = requireSnapshotJson(
                    interview.getPositionProfileSnapshot(), PositionProfileData.class);
            // 从创建快照重建上下文，并用实体中的持久化计数覆盖初始化默认值以恢复推进位置。
            InterviewContext context = coordinatorAgent.initialize(
                    interview, userProfile, userProfileAnalysis, positionProfile);
            syncContext(context, interview);

            // Coordinator 在事务外完成安全评估、确定性决策和下一题生成；失败由下方释放 reservation。
            TurnResult result = coordinatorAgent.coordinate(
                    context, interview, reserved.lastQuestion(), answer);
            // 精确比较 reservation 后原子双写回答和下一题，并把推进后的上下文保存到面试。
            turnStateService.complete(userId, reserved, answer, context, result);
            // 自然进入 ENDING 后数据库状态已结束；随后尽力释放对应 Redis 槽位。
            if (result.getPhase() == InterviewPhase.ENDING) {
                interviewSlotManager.releaseSlot(interviewId);
            }
            return result;
        } catch (RuntimeException failure) {
            releaseFailedTurn(userId, interviewId, reserved.reservationToken(), failure);
            throw failure;
        }
    }

    /**
     * 轮次失败时释放精确 token；补偿异常不能覆盖模型或写回的原始失败。
     * token 已被后续流程替换时状态服务按幂等结果返回，不会清除新轮次。
     */
    private void releaseFailedTurn(
            Long userId,
            Long interviewId,
            String reservationToken,
            RuntimeException originalFailure) {
        try {
            // 通过事务状态服务精确匹配 token，恢复本场再次提交回答的能力。
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
     * 主动结束当前用户的面试并释放数据库资源锁和 Redis 槽位。
     *
     * <p>进行中会话由 Coordinator 写入结束状态；已结束或已中断会话仍会尝试精确清理残留锁。
     * Redis 释放放在 {@code finally} 中，因此数据库流程成功或抛错都会执行一次幂等释放。</p>
     */
    @Transactional
    public InterviewDetailResponse endInterview(Long userId, Long interviewId) {
        try {
            // 对本人面试加写锁，避免主动结束与回答写回同时覆盖状态。
            Interview interview = findInterviewForUpdate(userId, interviewId);
            if (interview.getStatus() == InterviewStatus.IN_PROGRESS) {
                // Coordinator 将会话置为中断终态并设置结束时间；主动结束不会生成额外结束消息。
                coordinatorAgent.endInterview(interview, true);
                interview.setPendingQuestion(null);
                interviewRepository.save(interview);
            }
            // 按锁持有者 ID 精确释放简历和私有岗位，避免影响后来建立的面试。
            unlockResumeAndPosition(interview);
            return toDetailResponse(interview);
        } finally {
            interviewSlotManager.releaseSlot(interviewId);
        }
    }

    /** 按创建时间倒序查询当前用户全部面试，并逐条映射为会话详情。 */
    @Transactional(readOnly = true)
    public List<InterviewDetailResponse> listInterviews(Long userId) {
        // 仓储查询已限定用户归属，响应映射会对终态会话额外读取候选人消息计算分数。
        return interviewRepository.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(this::toDetailResponse)
                .toList();
    }

    /** 校验面试属于当前用户后，按消息序号升序返回完整问答记录。 */
    @Transactional(readOnly = true)
    public List<InterviewMessageResponse> listMessages(Long userId, Long interviewId) {
        // 先执行归属查询，再用仅含 interviewId 的消息仓储读取，防止跨用户访问问答。
        findInterview(userId, interviewId);
        return messageRepository.findByInterviewIdOrderBySeqNoAsc(interviewId).stream()
                .map(this::toMessageResponse)
                .toList();
    }

    /**
     * 获取当前用户面试的评估报告。
     *
     * <p>优先反序列化数据库已保存报告；未命中时读取完整消息和岗位快照，按当前本地公式
     * 组装评分，再调用报告 Agent 生成文本，执行有限字段替换后保存。本方法是 GET 接口调用
     * 的事务写路径，因此首次读取会生成并持久化报告，后续读取返回缓存实体。</p>
     */
    @Transactional
    public InterviewReportResponse getReport(Long userId, Long interviewId) {
        // 先校验会话归属；报告仓储自身只按 interviewId 查询，不承担用户隔离。
        Interview interview = findInterview(userId, interviewId);

        // 优先读取已持久化报告，避免重复执行评分、模型分析和数据库写入。
        InterviewReport cached = reportRepository.findByInterviewId(interviewId).orElse(null);
        if (cached != null) {
            log.info("[InterviewService] 命中已保存报告 interviewId={}", interviewId);
            return toReportResponse(cached);
        }

        // 缓存未命中时读取按序问答和创建时岗位快照，报告不回查实时岗位画像。
        List<InterviewMessage> messages = messageRepository.findByInterviewIdOrderBySeqNoAsc(interviewId);
        PositionProfileData positionProfile = requireSnapshotJson(
                interview.getPositionProfileSnapshot(), PositionProfileData.class);

        InterviewReportResponse report = new InterviewReportResponse();
        report.setInterviewId(interviewId);

        // 当前综合分只由候选人回答字符数计算，与 EvaluationResult 或 ThemeEvaluation 无关。
        int totalScore = calculateOverallScore(messages);
        report.setOverallScore(totalScore);
        report.setGrade(calculateGrade(totalScore));

        // 遍历固定环节枚举，统计各环节面试官消息数并按当前环节顺序推导完成状态。
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

        // 五维分数由综合分减固定偏移得到；偏移值的精确产品依据当前缺失。
        InterviewReportResponse.DimensionScores dimensions = new InterviewReportResponse.DimensionScores();
        dimensions.setTechnicalDepth(totalScore);
        dimensions.setTechnicalBreadth(totalScore - 3);
        dimensions.setPracticalExperience(totalScore - 1);
        dimensions.setExpression(totalScore - 2);
        dimensions.setLearningAbility(totalScore - 4);
        report.setDimensions(dimensions);

        // 报告 Agent 使用完整问答和岗位画像生成优劣势；模型/JSON 失败时由 Agent 本地降级。
        ReportAgent.AnalysisResult analysis = reportAgent.analyze(messages, positionProfile);
        report.setStrengths(analysis.strengths().isEmpty()
                ? List.of("参与面试态度积极") : analysis.strengths());
        report.setWeaknesses(analysis.weaknesses().isEmpty()
                ? List.of("建议补充更多实践案例和原理细节") : analysis.weaknesses());
        report.setKeyEvents(List.of());
        report.setConclusion("本次面试已完成，候选人整体表现" + report.getGrade()
                + "。" + String.join("；", analysis.weaknesses()));
        report.setMdContent(buildReportMarkdown(report));

        // 仅对报告四个文本区域做公司名精确替换；候选人姓名当前传 null，不触发姓名替换。
        InterviewReportResponse desensitized = reportDesensitizer.desensitize(
                report, interview.getCompanyNameSnapshot(), null);

        // 将列表和维度序列化进报告实体后保存，后续同一面试直接走缓存读取。
        InterviewReport entity = toReportEntity(userId, interviewId, desensitized);
        reportRepository.save(entity);
        log.info("[InterviewService] 报告已生成并保存 interviewId={}", interviewId);

        return desensitized;
    }

    /**
     * 根据结构化报告字段构建固定章节 Markdown；缓存报告读取时也会重新构建该正文。
     */
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

    /** 按面试 ID 与当前用户归属查询；不存在和越权使用同一业务错误。 */
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
     * 面试结束时精确释放简历和当前用户私有岗位锁定。
     * 公共岗位没有创建锁，私有岗位锁已变化时也不会被误清除。
     */
    private void unlockResumeAndPosition(Interview interview) {
        // 只有锁字段仍等于当前面试 ID 时才释放，避免并发覆盖后来会话的锁。
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

    /**
     * 用实体中已持久化的 Skill 推进状态覆盖新建上下文默认值，恢复断线前的主题、深度和计数。
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
     * 解析用户提交的环节名，去重、按固定顺序排序并保证末尾存在唯一结束环节。
     *
     * <p>当前实现自行执行 {@code distinct + sort + append}，并未调用
     * {@link InterviewPhase#sortSelected(List)}；因此旧注释所称的委托关系不成立。</p>
     */
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
        // distinct 已去除重复 ENDING；排序后仅在列表末尾不是 ENDING 时追加一次结束环节。
        if (phases.isEmpty() || phases.get(phases.size() - 1) != InterviewPhase.ENDING) {
            phases.add(InterviewPhase.ENDING);
        }
        return phases;
    }

    /**
     * 将会话实体映射为详情；终态会话会额外查询候选人消息并按当前字符数公式即时计算分数。
     */
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

        // 对终态面试按候选人回答重新计算分数；进行中响应保持分数与等级为空。
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

    /** 将持久化消息转换为 API 结构，并把本地时间格式化为不含时区的固定字符串。 */
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

    /**
     * 解析会话环节快照；历史空值返回固定五环节列表，坏 JSON 则返回空列表以保持既有兼容行为。
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

    /** 按输入顺序为环节编码生成中文 Label；未知历史编码由枚举 helper 原样返回。 */
    private Map<String, String> toPhaseLabels(List<String> phaseCodes) {
        Map<String, String> labels = new LinkedHashMap<>();
        for (String phaseCode : phaseCodes) {
            labels.put(phaseCode, InterviewPhase.displayNameOf(phaseCode));
        }
        return labels;
    }

    /** 将环节或报告结构序列化为数据库 TEXT 内容，失败统一转换为现有业务异常。 */
    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            throw new BusinessException(InterviewErrorCode.INVALID_PHASE.getCode(), "序列化失败", e);
        }
    }

    /**
     * 宽松解析可选 JSON：空值或坏值尝试构造空对象，无法无参构造时返回 {@code null}。
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

    /** 宽松解析泛型报告字段；空值或坏 JSON 返回 {@code null} 并由调用方选择空集合降级。 */
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

    /** 兼容历史上把 JSON 正文再次编码为 JSON 字符串的双层格式。 */
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
     * 按当前固定字符数公式计算综合评分。
     *
     * <p>空消息返回 60；只累计角色为 {@code candidate} 的正文长度，每条最多计 200 个
     * UTF-16 字符单元，每累计 50 个加 1 分，从 60 分起且最高 95 分。60、200、50、95
     * 的精确业务依据当前缺失；调高或调低会直接改变详情、报告和成长方案使用的分数。</p>
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
     * 根据综合评分映射中文等级：90 分起优秀、80 分起良好、60 分起合格，其余待提升。
     * 三个阈值的精确产品依据当前缺失，调整会改变详情、报告和成长方案展示结果。
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
     * 将已保存报告实体转换为响应对象。
     *
     * <p>JSON 字段解析失败会降级为空集合或空对象；环节 Label 按当前枚举重新映射，Markdown
     * 也根据结构化字段重新构建，而不是直接信任实体中的旧正文。</p>
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

    /** 将有限字段替换后的报告响应映射为实体，并把结构化字段序列化到 TEXT 列。 */
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
