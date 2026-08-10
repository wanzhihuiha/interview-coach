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
 * 面试官 Agent：由各环节 Skill 调用，按服务端状态构造类型化出题请求，并把已校验问题返回给面试流程。
 * 模型不能决定阶段、主题、深度或结束状态；模型、安全检查或输入构造失败时只返回固定安全模板。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InterviewerAgent {

    private final LlmInterviewService llmService;
    private final ObjectMapper objectMapper;
    private final SkillsTool skillsTool;
    private final QuestionBankTool questionBankTool;

    public String generateFirstQuestion(InterviewContext context) {
        return AgentContext.runAs(AgentType.INTERVIEWER, () -> {
            log.info("[InterviewerAgent] 加载 {} Skill 编排", context.getCurrentPhase());
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
     * 首题优先沿用既有永久题库采样；需要模型生成时，当前主题以及追问所需的上一问答都作为
     * DATA_ONLY 数据提交，目标深度仍由服务端参数固定。
     */
    public String generateProfessionalQuestion(InterviewContext context, String previousQuestion,
                                                String previousAnswer, int targetDepth) {
        return AgentContext.runAs(AgentType.INTERVIEWER, () -> {
            // 非追问场景优先从永久 RAG 采样题目，降低 LLM 调用并沉淀高质量题目
            if (previousQuestion == null) {
                String bankQuestion = sampleFromPermanentBank(context);
                if (bankQuestion != null) {
                    log.info("[InterviewerAgent] 命中永久 RAG 题目, interviewId={}, topic={}",
                            context.getInterviewId(), context.getCurrentTopicId());
                    return bankQuestion;
                }
            }

            InterviewQuestionKind kind = previousQuestion == null
                    ? InterviewQuestionKind.PROFESSIONAL
                    : InterviewQuestionKind.PROFESSIONAL_FOLLOW_UP;
            return generateQuestion(
                    kind,
                    () -> previousQuestion == null
                            ? InterviewQuestionRequest.professional(targetDepth)
                            : InterviewQuestionRequest.professionalFollowUp(targetDepth),
                    () -> {
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
     * 保留既有永久题库读取路径；题库安全治理不属于本次 LLM 出题安全试点，采样异常时回到安全模型入口。
     */
    private String sampleFromPermanentBank(InterviewContext context) {
        try {
            String jobCategory = context.getJobCategory() == null ? "GENERAL" : context.getJobCategory();
            String phase = InterviewPhase.PROFESSIONAL.name();
            String topicId = context.getCurrentTopicId();
            double ratio = questionBankTool.decideBankRatio(jobCategory, topicId);
            if (ratio <= 0.0) {
                return null;
            }
            List<QuestionBankItem> items;
            if (topicId != null && !topicId.isBlank()) {
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
     * {@code nextTopicId} 继续由服务端调用链维护以兼容现有签名，但不发送给模型，也不允许模型修改。
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

    public String generateResumeQuestion(InterviewContext context) {
        return AgentContext.runAs(AgentType.INTERVIEWER, () -> {
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

    public String generateBehavioralQuestion(InterviewContext context) {
        return AgentContext.runAs(AgentType.INTERVIEWER, () -> {
            int index = context.getCurrentBehavioralIndex() != null ? context.getCurrentBehavioralIndex() : 0;
            return generateQuestion(
                    InterviewQuestionKind.BEHAVIORAL,
                    () -> InterviewQuestionRequest.behavioral(index),
                    () -> buildPositionDataBlocks(context, false));
        });
    }

    public String generateEndingMessage(InterviewContext context) {
        return AgentContext.runAs(AgentType.INTERVIEWER, () -> {
            return generateQuestion(
                    InterviewQuestionKind.ENDING,
                    InterviewQuestionRequest::ending,
                    List::of);
        });
    }

    /**
     * 所有模型失败、输入越界或安全拒绝都只返回固定模板，不使用模型原文改变面试流程。
     */
    private String generateQuestion(
            InterviewQuestionKind fallbackKind,
            Supplier<InterviewQuestionRequest> requestSupplier,
            Supplier<List<LlmDataBlock>> dataBlocksSupplier) {
        try {
            InterviewQuestionRequest request = requestSupplier.get();
            if (!skillsTool.hasSkill(request.kind().getSkillPhase())) {
                log.warn("[InterviewerAgent] 面试 Skill 缺失，使用固定模板: kind={}", request.kind());
                return SafeInterviewQuestionTemplates.forKind(fallbackKind);
            }
            LlmTaskInput<InterviewQuestionRequest> input = new LlmTaskInput<>(
                    LlmTaskType.INTERVIEW_QUESTION_GENERATION,
                    request,
                    dataBlocksSupplier.get());
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

    private void addTextBlock(
            List<LlmDataBlock> blocks,
            String blockId,
            LlmDataSource source,
            String text) {
        if (text != null && !text.isBlank()) {
            blocks.add(new LlmDataBlock(blockId, source, text));
        }
    }

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
