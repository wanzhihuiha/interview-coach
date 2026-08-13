package com.interviewcoach.interview.domain.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.interviewcoach.common.llm.LlmDataBlock;
import com.interviewcoach.common.llm.LlmDataSource;
import com.interviewcoach.common.llm.LlmExecutionResult;
import com.interviewcoach.common.llm.LlmFailureType;
import com.interviewcoach.common.llm.LlmTaskInput;
import com.interviewcoach.common.llm.LlmTaskType;
import com.interviewcoach.common.security.agent.AgentContext;
import com.interviewcoach.common.security.agent.AgentType;
import com.interviewcoach.interview.application.dto.QuestionBankItem;
import com.interviewcoach.interview.domain.entity.InterviewPhase;
import com.interviewcoach.interview.domain.model.InterviewContext;
import com.interviewcoach.interview.infrastructure.tool.QuestionBankTool;
import com.interviewcoach.interview.infrastructure.tool.SkillsTool;
import com.interviewcoach.resume.domain.model.ResumeProfileAnalysisData;
import com.interviewcoach.resume.domain.model.UserProfileData;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 各环节 Skill 共用的受限出题 Agent。
 *
 * <p>Skill 提供服务端题型、主题、深度和项目/场景索引，本组件优先读取永久题库或构造类型化
 * LLM 任务，再把严格校验后的单字段问题返回给 Coordinator。模型不能决定阶段、主题、深度
 * 或结束状态；Skill 缺失、输入无效、安全拒绝或模型失败时只返回对应固定模板。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InterviewerAgent {

    /** 负责出题任务的 Agent 权限、限流、安全检测、模型传输和严格响应解析。 */
    private final LlmInterviewService llmService;
    /** 负责把有限辅助分析线索序列化为无指令权限 JSON 数据块。 */
    private final ObjectMapper objectMapper;
    /** 负责确认题型对应的服务端 Markdown Skill 已加载。 */
    private final SkillsTool skillsTool;
    /** 负责永久题库启用判断、按使用次数取候选题和临时题附带写入。 */
    private final QuestionBankTool questionBankTool;

    /**
     * 根据当前服务端环节选择对应首题入口；未知于四个业务环节的值统一按结束环节处理。
     */
    public String generateFirstQuestion(InterviewContext context) {
        return AgentContext.runAs(AgentType.INTERVIEWER, () -> {
            log.info("[InterviewerAgent] 加载 {} Skill 编排", context.getCurrentPhase());
            // 当前环节只由 Coordinator 状态机提供，模型不参与此分支选择。
            if (context.getCurrentPhase() == InterviewPhase.SELF_INTRO) {
                return generateSelfIntroQuestion(context);
            }
            if (context.getCurrentPhase() == InterviewPhase.PROFESSIONAL) {
                return generateProfessionalQuestion(context, null, null, 1);
            }
            if (context.getCurrentPhase() == InterviewPhase.RESUME_DISCUSSION) {
                return generateResumeQuestion(context);
            }
            if (context.getCurrentPhase() == InterviewPhase.BEHAVIORAL) {
                return generateBehavioralQuestion(context);
            }
            return generateEndingMessage(context);
        });
    }

    /**
     * 用自我介绍已提问计数构造可信参数，岗位展示字段只作为无指令权限数据生成引导题。
     */
    public String generateSelfIntroQuestion(InterviewContext context) {
        return AgentContext.runAs(AgentType.INTERVIEWER, () -> {
            return generateQuestion(
                    InterviewQuestionKind.SELF_INTRO,
                    () -> InterviewQuestionRequest.selfIntro(
                            context.getSelfIntroQuestionCount() == null
                                    ? 0 : context.getSelfIntroQuestionCount()),
                    () -> buildPositionDataBlocks(context, false));
        });
    }

    /**
     * 专业主题首题优先沿用既有永久题库读取；需要模型生成时，当前主题以及追问所需的上一
     * 问答都作为 DATA_ONLY 数据提交，目标深度仍由服务端参数固定。
     */
    public String generateProfessionalQuestion(InterviewContext context, String previousQuestion,
                                                String previousAnswer, int targetDepth) {
        return AgentContext.runAs(AgentType.INTERVIEWER, () -> {
            // 非追问场景先按固定筛选和 usageCount 顺序取永久题；不执行随机抽样。
            if (previousQuestion == null) {
                String bankQuestion = sampleFromPermanentBank(context);
                if (bankQuestion != null) {
                    log.info("[InterviewerAgent] 命中永久 RAG 题目, interviewId={}, topic={}",
                            context.getInterviewId(), context.getCurrentTopicId());
                    return bankQuestion;
                }
            }

            // 是否存在上一问决定首题或追问题型；targetDepth 始终由专业 Skill 算出。
            InterviewQuestionKind kind = previousQuestion == null
                    ? InterviewQuestionKind.PROFESSIONAL
                    : InterviewQuestionKind.PROFESSIONAL_FOLLOW_UP;
            return generateQuestion(
                    kind,
                    () -> previousQuestion == null
                            ? InterviewQuestionRequest.professional(targetDepth)
                            : InterviewQuestionRequest.professionalFollowUp(targetDepth),
                    () -> {
                        // 岗位字段、辅助分析、主题和上一问答全部作为无指令权限数据块。
                        List<LlmDataBlock> blocks = buildPositionDataBlocks(context, true);
                        addRequiredTextBlock(blocks, "current-topic", LlmDataSource.MODEL_DERIVED_CONTENT,
                                context.getCurrentTopicName());
                        if (previousQuestion != null) {
                            addRequiredTextBlock(blocks, "previous-question", LlmDataSource.INTERVIEW_QUESTION,
                                    previousQuestion);
                            addRequiredTextBlock(blocks, "previous-answer", LlmDataSource.INTERVIEW_ANSWER,
                                    previousAnswer);
                        }
                        return blocks;
                    });
        });
    }

    /**
     * 尝试从永久题库读取一个专业题。
     *
     * <p>{@code decideBankRatio} 的返回值当前只作为是否启用题库的开关，正值不会按概率抽样；
     * 查询结果按 {@code usageCount} 升序，由工具在内存截取第一条且不会累加使用次数。读取异常
     * 只记录告警并返回空，让调用方进入安全模型路径。</p>
     */
    private String sampleFromPermanentBank(InterviewContext context) {
        try {
            String jobCategory = context.getJobCategory() == null ? "GENERAL" : context.getJobCategory();
            String phase = InterviewPhase.PROFESSIONAL.name();
            String topicId = context.getCurrentTopicId();
            // 分段比例当前未用于概率选择，只要大于 0 即启用题库读取。
            double ratio = questionBankTool.decideBankRatio(jobCategory, topicId);
            if (ratio <= 0.0) {
                return null;
            }
            List<QuestionBankItem> items;
            if (topicId != null && !topicId.isBlank()) {
                // 有主题时精确筛选主题；工具按使用次数升序后取首条。
                items = questionBankTool.sampleFromPermanent(jobCategory, phase, topicId, 1);
            } else {
                items = questionBankTool.sampleFromPermanent(jobCategory, phase, 1);
            }
            if (items != null && !items.isEmpty()) {
                return items.get(0).getContent();
            }
        } catch (Exception e) {
            log.warn("[InterviewerAgent] 采样永久 RAG 失败，降级使用 LLM: {}", e.getMessage());
        }
        return null;
    }

    /**
     * 生成专业主题切换题；下一主题名称作为模型派生数据块，题型和流程由服务端固定。
     * {@code nextTopicId} 只为兼容现有签名，当前不发送给模型也不在本方法使用。
     */
    public String generateTopicTransition(InterviewContext context, String nextTopicName, String nextTopicId) {
        return AgentContext.runAs(AgentType.INTERVIEWER, () -> {
            return generateQuestion(
                    InterviewQuestionKind.TOPIC_TRANSITION,
                    InterviewQuestionRequest::topicTransition,
                    () -> {
                        List<LlmDataBlock> blocks = new ArrayList<>();
                        addRequiredTextBlock(
                                blocks, "next-topic", LlmDataSource.MODEL_DERIVED_CONTENT, nextTopicName);
                        return blocks;
                    });
        });
    }

    /**
     * 按上下文项目索引读取正式简历事实快照中的项目名称，并作为 VERIFIED_FACTS 数据块生成核验题。
     * 项目缺失或索引越界会在必需数据块校验处失败，最终降级为固定简历问题。
     */
    public String generateResumeQuestion(InterviewContext context) {
        return AgentContext.runAs(AgentType.INTERVIEWER, () -> {
            // 只读取创建时固化的正式事实项目，不把辅助分析中的推断项目当成已证实事实。
            UserProfileData profile = context.getUserProfile();
            List<UserProfileData.ProjectExperience> projects = profile != null ? profile.getProjectExperience() : null;
            int index = context.getCurrentProjectIndex() != null ? context.getCurrentProjectIndex() : 0;
            UserProfileData.ProjectExperience project = projects != null && index >= 0 && index < projects.size()
                    ? projects.get(index) : null;
            String projectName = project == null ? null : project.getName();
            return generateQuestion(
                    InterviewQuestionKind.RESUME_DISCUSSION,
                    () -> InterviewQuestionRequest.resumeDiscussion(index),
                    () -> {
                        List<LlmDataBlock> blocks = buildPositionDataBlocks(context, true);
                        addRequiredTextBlock(
                                blocks, "current-project", LlmDataSource.VERIFIED_FACTS, projectName);
                        return blocks;
                    });
        });
    }

    /** 按服务端行为场景索引构造可信请求，并用可选岗位字段生成 STAR 风格问题。 */
    public String generateBehavioralQuestion(InterviewContext context) {
        return AgentContext.runAs(AgentType.INTERVIEWER, () -> {
            int index = context.getCurrentBehavioralIndex() != null ? context.getCurrentBehavioralIndex() : 0;
            return generateQuestion(
                    InterviewQuestionKind.BEHAVIORAL,
                    () -> InterviewQuestionRequest.behavioral(index),
                    () -> buildPositionDataBlocks(context, false));
        });
    }

    /** 生成不携带外部数据的结束语；模型失败时返回固定结束模板。 */
    public String generateEndingMessage(InterviewContext context) {
        return AgentContext.runAs(AgentType.INTERVIEWER, () -> {
            return generateQuestion(
                    InterviewQuestionKind.ENDING,
                    InterviewQuestionRequest::ending,
                    List::of);
        });
    }

    /**
     * 统一执行出题安全任务。所有 Skill 缺失、模型失败、输入越界或安全拒绝都只返回固定模板，
     * 不使用模型原文改变面试流程。
     */
    private String generateQuestion(
            InterviewQuestionKind fallbackKind,
            Supplier<InterviewQuestionRequest> requestSupplier,
            Supplier<List<LlmDataBlock>> dataBlocksSupplier) {
        try {
            // 先构造并校验可信服务端参数；失败被当前方法捕获并走固定模板。
            InterviewQuestionRequest request = requestSupplier.get();
            if (!skillsTool.hasSkill(request.kind().getSkillPhase())) {
                log.warn("[InterviewerAgent] 面试 Skill 缺失，使用固定模板: kind={}", request.kind());
                return SafeInterviewQuestionTemplates.forKind(fallbackKind);
            }
            // 外部文本数据块与服务端参数分离后交给类型化安全网关。
            LlmTaskInput<InterviewQuestionRequest> input = new LlmTaskInput<>(
                    LlmTaskType.INTERVIEW_QUESTION_GENERATION,
                    request,
                    dataBlocksSupplier.get());
            // 安全入口完成输入检测、模型调用、严格 JSON 解析和输出风险复检。
            LlmExecutionResult<InterviewQuestionResponse> result = llmService.execute(
                    input, InterviewQuestionResponse.class);
            if (result instanceof LlmExecutionResult.Success<?> success
                    && success.value() instanceof InterviewQuestionResponse response) {
                return response.question();
            }
            LlmFailureType failureType = result instanceof LlmExecutionResult.Failure<?> failure
                    ? failure.failureType() : LlmFailureType.UNEXPECTED_FAILURE;
            log.warn("[InterviewerAgent] 模型出题不可用，使用固定模板: kind={}, failureType={}",
                    fallbackKind, failureType);
        } catch (RuntimeException e) {
            log.warn("[InterviewerAgent] 面试题请求无效，使用固定模板: kind={}, errorType={}",
                    fallbackKind, e.getClass().getSimpleName());
        }
        return SafeInterviewQuestionTemplates.forKind(fallbackKind);
    }

    /**
     * 组装岗位标题、级别及可选辅助分析数据块；所有内容均标记为模型派生且不具备指令权限。
     */
    private List<LlmDataBlock> buildPositionDataBlocks(
            InterviewContext context, boolean includeAnalysisHints) {
        List<LlmDataBlock> blocks = new ArrayList<>();
        if (context.getPositionProfile() != null && context.getPositionProfile().getBasicInfo() != null) {
            addTextBlock(blocks, "position-title", LlmDataSource.MODEL_DERIVED_CONTENT,
                    context.getPositionProfile().getBasicInfo().getTitle());
            addTextBlock(blocks, "position-level", LlmDataSource.MODEL_DERIVED_CONTENT,
                    context.getPositionProfile().getBasicInfo().getLevel());
        }
        if (includeAnalysisHints) {
            addTextBlock(blocks, "analysis-hints", LlmDataSource.MODEL_DERIVED_CONTENT,
                    buildAnalysisHintsJson(context.getUserProfileAnalysis()));
        }
        return blocks;
    }

    /**
     * 只提取有限数量的辅助分析线索，并使用结构化 JSON 放入 MODEL_DERIVED_CONTENT 数据块。
     */
    private String buildAnalysisHintsJson(ResumeProfileAnalysisData analysis) {
        if (analysis == null) {
            return null;
        }
        List<String> strengths = analysis.getStrengths() == null ? List.of()
                : analysis.getStrengths().stream()
                .filter(Objects::nonNull)
                .map(ResumeProfileAnalysisData.AnalysisItem::getContent)
                .filter(content -> content != null && !content.isBlank())
                .limit(3)
                .toList();
        List<String> verificationPoints = analysis.getVerificationPoints() == null ? List.of()
                : analysis.getVerificationPoints().stream()
                .filter(Objects::nonNull)
                .map(ResumeProfileAnalysisData.AnalysisItem::getContent)
                .filter(content -> content != null && !content.isBlank())
                .limit(3)
                .toList();
        List<String> skillAssessments = analysis.getSkillAssessments() == null ? List.of()
                : analysis.getSkillAssessments().stream()
                .filter(item -> item != null && item.getSkill() != null)
                .map(item -> item.getSkill() + "："
                        + (item.getInferredLevel() == null ? "待验证" : item.getInferredLevel()))
                .limit(5)
                .toList();
        if (strengths.isEmpty() && verificationPoints.isEmpty() && skillAssessments.isEmpty()) {
            return null;
        }
        Map<String, Object> hints = new LinkedHashMap<>();
        hints.put("possibleStrengthsRequiringVerification", strengths);
        hints.put("verificationPoints", verificationPoints);
        hints.put("inferredSkillLevelsRequiringVerification", skillAssessments);
        try {
            return objectMapper.writeValueAsString(hints);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("面试分析线索无法序列化", e);
        }
    }

    /** 可选文本非空时加入数据块；空文本直接省略。 */
    private void addTextBlock(
            List<LlmDataBlock> blocks,
            String blockId,
            LlmDataSource source,
            String text) {
        if (text != null && !text.isBlank()) {
            blocks.add(new LlmDataBlock(blockId, source, text));
        }
    }

    /** 必需文本非空时加入数据块；缺失时抛出参数异常并由统一出题入口降级固定模板。 */
    private void addRequiredTextBlock(
            List<LlmDataBlock> blocks,
            String blockId,
            LlmDataSource source,
            String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("面试出题缺少必要数据块: " + blockId);
        }
        blocks.add(new LlmDataBlock(blockId, source, text));
    }
}
