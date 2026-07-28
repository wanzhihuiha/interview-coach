package com.interviewcoach.interview.infrastructure.tool;

import com.interviewcoach.common.security.agent.AgentPermission;
import com.interviewcoach.common.security.agent.AgentType;
import com.interviewcoach.interview.domain.model.EvaluationResult;
import com.interviewcoach.interview.domain.model.EvaluationSignal;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 评估降级工具：当 LLM 评估失败或超时时，使用规则评估兜底。
 */
@Component
public class EvaluationFallbackTool {

    public EvaluationResult ruleEvaluate(String question, String answer) {
        EvaluationResult result = new EvaluationResult();
        int length = answer == null ? 0 : answer.length();

        boolean hasNegative = answer != null && (answer.contains("不知道")
                || answer.contains("不了解") || answer.contains("不会") || answer.contains("没做过"));
        boolean tooShort = length < 10;
        boolean tooLong = length > 2000;

        int baseScore = 70;
        if (hasNegative) baseScore -= 25;
        if (tooShort) baseScore -= 15;
        if (tooLong) baseScore -= 5;

        result.setOverall(baseScore >= 80 ? "良好" : baseScore >= 60 ? "一般" : "较差");
        result.setTechnicalDepth(baseScore);
        result.setTechnicalBreadth(baseScore);
        result.setPracticalExperience(baseScore);
        result.setExpression(baseScore);
        result.setLearningAbility(baseScore);
        result.setSuggestedNextDepth(tooShort || hasNegative ? 1 : 3);
        result.setKeyEvent(hasNegative || tooShort ? "STRUGGLED" : null);
        result.setKeyEventReason(hasNegative ? "回答包含消极表达" : (tooShort ? "回答过短" : null));
        result.setStrengths(baseScore >= 80 ? List.of("回答较为完整") : List.of());
        result.setWeaknesses(hasNegative ? List.of("存在消极表达") : (tooShort ? List.of("回答过于简短") : List.of()));
        result.setComment("LLM 评估超时/失败，已采用规则兜底评估。");
        return result;
    }

    @AgentPermission({AgentType.EVALUATOR, AgentType.COORDINATOR})
    public EvaluationSignal extractSignal(EvaluationResult result) {
        EvaluationSignal signal = new EvaluationSignal();
        signal.setSuggestedNextDepth(result.getSuggestedNextDepth());
        signal.setKeyEventType(result.getKeyEvent());
        signal.setContinueProbing(!"STRUGGLED".equalsIgnoreCase(result.getKeyEvent())
                && result.getSuggestedNextDepth() != null);
        signal.setBriefComment(result.getComment());
        return signal;
    }
}
