package com.interviewcoach.interview.domain.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.interviewcoach.common.security.agent.AgentContext;
import com.interviewcoach.common.security.agent.AgentType;
import com.interviewcoach.interview.application.dto.QuestionBankItem;
import com.interviewcoach.interview.domain.model.EvaluationResult;
import com.interviewcoach.interview.domain.model.EvaluationSignal;
import com.interviewcoach.interview.domain.model.InterviewContext;
import com.interviewcoach.interview.infrastructure.tool.QuestionBankTool;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 评估者 Agent：负责评估候选人回答质量。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EvaluatorAgent {

    private final LlmInterviewService llmService;
    private final QuestionBankTool questionBankTool;
    private final ObjectMapper objectMapper;

    public EvaluationResult evaluate(InterviewContext context, String question, String answer) {
        return AgentContext.runAs(AgentType.EVALUATOR, () -> {
            String system = "你是一个专业的面试评估官，负责评估候选人的回答质量。只输出 JSON。";
            StringBuilder user = new StringBuilder();
            user.append("**面试上下文**\n");
            if (context.getPositionProfile() != null && context.getPositionProfile().getBasicInfo() != null) {
                user.append("- 岗位：").append(context.getPositionProfile().getBasicInfo().getTitle()).append("\n");
            }
            user.append("- 当前环节：").append(context.getCurrentPhase().getDisplayName()).append("\n");
            if (context.getCurrentTopicName() != null) {
                user.append("- 当前主题：").append(context.getCurrentTopicName()).append("\n");
            }
            user.append("- 问题：").append(question).append("\n");
            user.append("- 候选人回答：").append(answer).append("\n");
            user.append("\n评估要求：\n");
            user.append("1. 判断回答质量：优秀/良好/一般/较差/很差\n");
            user.append("2. 评估各维度得分（0-100）：technicalDepth、technicalBreadth、practicalExperience、expression、learningAbility\n");
            user.append("3. 给出下一问题的建议深度（1-5）\n");
            user.append("4. 如有突出亮点或明显不足，keyEvent 填 EXCELLENT/STRUGGLED，否则 null\n");
            user.append("输出格式：{\"assessment\":{\"overall\":\"...\",\"technicalDepth\":80,...}}");

            try {
                String response = llmService.chat(system, user.toString());
                EvaluationResult result = parseEvaluation(response);
                // 评估完成后，将本轮问题写入临时 RAG 供后续审核入库
                saveQuestionToTemporaryRag(context, question);
                return result;
            } catch (Exception e) {
                log.warn("[EvaluatorAgent] LLM 评估失败，返回空评估: {}", e.getMessage());
                return null;
            }
        });
    }

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

    public EvaluationSignal extractSignal(EvaluationResult result) {
        EvaluationSignal signal = new EvaluationSignal();
        if (result == null) {
            signal.setSuggestedNextDepth(1);
            signal.setContinueProbing(true);
            return signal;
        }
        signal.setSuggestedNextDepth(clampDepth(result.getSuggestedNextDepth()));
        signal.setKeyEventType(result.getKeyEvent());
        signal.setContinueProbing(!"STRUGGLED".equalsIgnoreCase(result.getKeyEvent()));
        signal.setBriefComment(result.getComment());
        return signal;
    }

    private EvaluationResult parseEvaluation(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            String cleaned = json.trim();
            if (cleaned.startsWith("```")) {
                cleaned = cleaned.replaceAll("^```(json)?\\s*", "").replaceAll("\\s*```$", "").trim();
            }
            Map<?, ?> root = objectMapper.readValue(cleaned, Map.class);
            Object assessmentObj = root.get("assessment");
            if (assessmentObj == null) {
                return null;
            }
            Map<?, ?> assessment = (Map<?, ?>) assessmentObj;
            EvaluationResult result = new EvaluationResult();
            result.setOverall(toString(assessment.get("overall")));
            result.setTechnicalDepth(toInt(assessment.get("technicalDepth")));
            result.setTechnicalBreadth(toInt(assessment.get("technicalBreadth")));
            result.setPracticalExperience(toInt(assessment.get("practicalExperience")));
            result.setExpression(toInt(assessment.get("expression")));
            result.setLearningAbility(toInt(assessment.get("learningAbility")));
            result.setSuggestedNextDepth(clampDepth(toInt(assessment.get("suggestedNextDepth"))));
            result.setKeyEvent(toString(assessment.get("keyEvent")));
            result.setKeyEventReason(toString(assessment.get("keyEventReason")));
            result.setComment(toString(assessment.get("comment")));
            result.setStrengths(toStringList(assessment.get("strengths")));
            result.setWeaknesses(toStringList(assessment.get("weaknesses")));
            return result;
        } catch (Exception e) {
            log.warn("[EvaluatorAgent] 评估 JSON 解析失败: {}", e.getMessage());
            return fallbackParse(json);
        }
    }

    private EvaluationResult fallbackParse(String raw) {
        EvaluationResult result = new EvaluationResult();
        Matcher overall = Pattern.compile("\"overall\"\\s*:\\s*\"([^\"]+)\"").matcher(raw);
        if (overall.find()) result.setOverall(overall.group(1));
        Matcher depth = Pattern.compile("\"suggestedNextDepth\"\\s*:\\s*(\\d+)").matcher(raw);
        if (depth.find()) result.setSuggestedNextDepth(clampDepth(Integer.parseInt(depth.group(1))));
        Matcher keyEvent = Pattern.compile("\"keyEvent\"\\s*:\\s*\"([^\"]+)\"").matcher(raw);
        if (keyEvent.find()) result.setKeyEvent(keyEvent.group(1));
        return result;
    }

    private int clampDepth(Integer depth) {
        if (depth == null) return 1;
        return Math.max(1, Math.min(5, depth));
    }

    private String toString(Object obj) {
        return obj == null ? null : obj.toString();
    }

    private int toInt(Object obj) {
        if (obj == null) return 70;
        if (obj instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(obj.toString());
        } catch (NumberFormatException e) {
            return 70;
        }
    }

    @SuppressWarnings("unchecked")
    private List<String> toStringList(Object obj) {
        if (obj instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        return null;
    }
}
