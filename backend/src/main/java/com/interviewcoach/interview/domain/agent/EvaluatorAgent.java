package com.interviewcoach.interview.domain.agent;

import com.interviewcoach.common.llm.LlmDataBlock;
import com.interviewcoach.common.llm.LlmDataSource;
import com.interviewcoach.common.llm.LlmExecutionResult;
import com.interviewcoach.common.llm.LlmFailureType;
import com.interviewcoach.common.llm.LlmTaskInput;
import com.interviewcoach.common.llm.LlmTaskType;
import com.interviewcoach.common.security.agent.AgentContext;
import com.interviewcoach.common.security.agent.AgentType;
import com.interviewcoach.interview.application.dto.QuestionBankItem;
import com.interviewcoach.interview.domain.model.EvaluationResult;
import com.interviewcoach.interview.domain.model.InterviewContext;
import com.interviewcoach.interview.infrastructure.tool.QuestionBankTool;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 评估者 Agent：把单轮问题和回答作为无指令权限的数据提交到安全入口，只返回已校验的评分结果。
 * 下一题深度、主题切换和结束状态不由本 Agent 决定。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EvaluatorAgent {

    private final LlmInterviewService llmService;
    private final QuestionBankTool questionBankTool;

    /**
     * 执行单轮回答评估；模型、安全检测或响应校验失败时返回 {@code null}。
     * 安全入口完成一次执行后，仍沿用既有行为把本轮问题写入临时题库，写入失败不影响评估结果。
     */
    public EvaluationResult evaluate(InterviewContext context, String question, String answer) {
        return AgentContext.runAs(AgentType.EVALUATOR, () -> {
            try {
                // 岗位、主题、问题和回答都可能包含提示词攻击，只能作为无指令权限的数据块传入。
                List<LlmDataBlock> dataBlocks = buildDataBlocks(context, question, answer);
                // 面试环节和题目深度来自服务端状态机，可以作为可信任务参数约束模型。
                InterviewAnswerEvaluationTaskDefinition.Parameters parameters =
                        new InterviewAnswerEvaluationTaskDefinition.Parameters(
                                context.getCurrentPhase(), context.getCurrentDepth());
                LlmTaskInput<InterviewAnswerEvaluationTaskDefinition.Parameters> input =
                        new LlmTaskInput<>(
                                LlmTaskType.INTERVIEW_ANSWER_EVALUATION, parameters, dataBlocks);
                LlmExecutionResult<EvaluationResult> executionResult =
                        llmService.execute(input, EvaluationResult.class);
                // 安全入口返回后，沿用原流程把本轮问题保存到临时题库，供后续人工审核是否入库。
                saveQuestionToTemporaryRag(context, question);
                // 只有安全网关明确返回成功时才把评分交给协调器；失败对象中不包含模型原文。
                if (executionResult instanceof LlmExecutionResult.Success<?> success
                        && success.value() instanceof EvaluationResult result) {
                    return result;
                }
                LlmFailureType failureType = executionResult instanceof LlmExecutionResult.Failure<?> failure
                        ? failure.failureType() : LlmFailureType.UNEXPECTED_FAILURE;
                log.warn("[EvaluatorAgent] 本轮评估不可用: failureType={}", failureType);
            } catch (Exception e) {
                log.warn("[EvaluatorAgent] 回答评估请求无效: errorType={}",
                        e.getClass().getSimpleName());
            }
            return null;
        });
    }

    /**
     * 组装无指令权限的数据块。来源枚举用于标记文本来自哪里，但不会提升文本的可信等级。
     */
    private List<LlmDataBlock> buildDataBlocks(
            InterviewContext context, String question, String answer) {
        List<LlmDataBlock> dataBlocks = new ArrayList<>();
        if (context.getPositionProfile() != null
                && context.getPositionProfile().getBasicInfo() != null) {
            addOptionalDataBlock(
                    dataBlocks,
                    "position-title",
                    LlmDataSource.MODEL_DERIVED_CONTENT,
                    context.getPositionProfile().getBasicInfo().getTitle());
        }
        addOptionalDataBlock(
                dataBlocks,
                "current-topic",
                LlmDataSource.MODEL_DERIVED_CONTENT,
                context.getCurrentTopicName());
        dataBlocks.add(new LlmDataBlock(
                "question", LlmDataSource.INTERVIEW_QUESTION, question));
        dataBlocks.add(new LlmDataBlock(
                "answer", LlmDataSource.INTERVIEW_ANSWER, answer));
        return dataBlocks;
    }

    private void addOptionalDataBlock(
            List<LlmDataBlock> dataBlocks,
            String blockId,
            LlmDataSource source,
            String text) {
        if (text != null && !text.isBlank()) {
            dataBlocks.add(new LlmDataBlock(blockId, source, text));
        }
    }

    /**
     * 尽力把有效问题写入临时题库。这是评估之外的附带写入：失败只记录告警，不能把评估改成失败。
     */
    private void saveQuestionToTemporaryRag(InterviewContext context, String question) {
        try {
            if (question == null || question.isBlank()) {
                return;
            }
            QuestionBankItem item = new QuestionBankItem();
            item.setJobCategory(context.getJobCategory() == null ? "GENERAL" : context.getJobCategory());
            item.setPhase(context.getCurrentPhase() == null ? "" : context.getCurrentPhase().name());
            item.setTopicId(context.getCurrentTopicId());
            item.setTopicName(context.getCurrentTopicName());
            item.setContent(question);
            item.setExpectedAnswer(null);
            item.setId(context.getInterviewId());
            item.setDifficultyLevel(context.getCurrentDepth() != null ? context.getCurrentDepth() : 3);
            questionBankTool.saveTemporaryQuestion(item);
        } catch (Exception e) {
            log.warn("[EvaluatorAgent] 写入临时 RAG 失败，不影响评估流程: {}", e.getMessage());
        }
    }

}
