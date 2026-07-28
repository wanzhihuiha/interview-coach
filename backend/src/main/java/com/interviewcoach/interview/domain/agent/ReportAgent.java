package com.interviewcoach.interview.domain.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.interviewcoach.common.security.agent.AgentContext;
import com.interviewcoach.common.security.agent.AgentType;
import com.interviewcoach.interview.domain.entity.InterviewMessage;
import com.interviewcoach.position.domain.model.PositionProfileData;
import com.interviewcoach.resume.infrastructure.ai.LlmService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 报告 Agent：基于面试问答记录生成具体的优势与薄弱点。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReportAgent {

    private final LlmService llmService;
    private final ObjectMapper objectMapper;

    /**
     * 分析面试问答，生成具体优势与薄弱点。
     *
     * @param messages       面试消息记录
     * @param positionProfile 岗位画像
     * @return 包含 strengths 和 weaknesses 的分析结果
     */
    public AnalysisResult analyze(List<InterviewMessage> messages, PositionProfileData positionProfile) {
        return AgentContext.runAs(AgentType.REPORT, () -> {
            String qaText = buildQaText(messages);
            String skillsText = buildSkillsText(positionProfile);

            String system = "你是一名资深技术面试官，正在根据面试问答记录撰写评估报告。"
                    + "请严格基于候选人的实际回答内容，输出具体、可落地的优势和薄弱点。"
                    + "不要输出抽象评价如'部分问题可进一步深入'。"
                    + "薄弱点必须采用'技术领域：具体问题'的格式，例如'JVM 内存区域：未掌握堆、虚拟机栈、方法区等核心概念'、'项目数据量化：自我介绍缺少 QPS/RT/用户量等可量化指标'。"
                    + "输出合法 JSON，格式：{\"strengths\":[\"具体优势1\",\"具体优势2\"],\"weaknesses\":[\"技术领域1：具体问题1\",\"技术领域2：具体问题2\",\"技术领域3：具体问题3\"]}";
            String user = "岗位核心要求：\n" + skillsText + "\n\n面试问答记录：\n" + qaText
                    + "\n\n请输出具体的优势和薄弱点。薄弱点必须是'技术领域：具体问题'的格式，便于后续生成针对性学习方案。";

            try {
                String json = llmService.chat(system, user);
                String cleaned = json.trim();
                if (cleaned.startsWith("```")) {
                    cleaned = cleaned.replaceAll("^```(json)?\\s*", "").replaceAll("\\s*```$", "").trim();
                }
                Map<?, ?> map = objectMapper.readValue(cleaned, Map.class);
                List<String> strengths = extractList(map.get("strengths"));
                List<String> weaknesses = extractList(map.get("weaknesses"));
                return new AnalysisResult(strengths, weaknesses);
            } catch (Exception e) {
                log.warn("[ReportAgent] LLM 分析失败，使用兜底分析: {}", e.getMessage());
                return fallbackAnalysis(messages, positionProfile);
            }
        });
    }

    private String buildQaText(List<InterviewMessage> messages) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < messages.size(); i++) {
            InterviewMessage m = messages.get(i);
            String role = "interviewer".equals(m.getRole()) ? "面试官" : "候选人";
            sb.append(role).append("：").append(m.getContent()).append("\n");
            if ("interviewer".equals(m.getRole()) && m.getTopicName() != null) {
                sb.append("[主题：").append(m.getTopicName()).append("，深度：L").append(m.getDepth()).append("]\n");
            }
        }
        return sb.toString();
    }

    private String buildSkillsText(PositionProfileData profile) {
        if (profile == null || profile.getRequiredSkills() == null) {
            return "未提供岗位画像";
        }
        return profile.getRequiredSkills().stream()
                .map(s -> s.getSkill() + "（重要度：" + s.getImportance() + "，深度：" + s.getDepth() + "）")
                .collect(Collectors.joining("\n"));
    }

    @SuppressWarnings("unchecked")
    private List<String> extractList(Object obj) {
        if (obj instanceof List<?> list) {
            return list.stream().map(String::valueOf).filter(s -> !s.isBlank()).toList();
        }
        return new ArrayList<>();
    }

    private AnalysisResult fallbackAnalysis(List<InterviewMessage> messages, PositionProfileData profile) {
        List<String> strengths = new ArrayList<>();
        List<String> weaknesses = new ArrayList<>();

        int candidateMsgCount = (int) messages.stream().filter(m -> "candidate".equals(m.getRole())).count();
        if (candidateMsgCount > 2) {
            strengths.add("能够持续参与多轮问答，表达意愿较好");
        }

        // 根据岗位画像生成期望技能缺口
        if (profile != null && profile.getRequiredSkills() != null && !profile.getRequiredSkills().isEmpty()) {
            String topSkill = profile.getRequiredSkills().get(0).getSkill();
            weaknesses.add("对岗位核心技能「" + topSkill + "」的深度掌握情况在面试中体现不足");
        }

        // 根据深度判断
        int maxDepth = messages.stream().filter(m -> m.getDepth() != null).mapToInt(InterviewMessage::getDepth).max().orElse(1);
        if (maxDepth <= 2) {
            weaknesses.add("技术追问深度停留在 L1-L2，原理机制和实践踩坑类问题回答较少");
        }

        // 根据回答长度判断
        double avgLength = messages.stream()
                .filter(m -> "candidate".equals(m.getRole()))
                .mapToInt(m -> m.getContent() == null ? 0 : m.getContent().length())
                .average().orElse(0);
        if (avgLength < 80) {
            weaknesses.add("回答较为简短，缺少展开说明和案例支撑");
        }

        if (strengths.isEmpty()) {
            strengths.add("参与面试态度积极");
        }
        if (weaknesses.isEmpty()) {
            weaknesses.add("建议后续面试中补充更多实践案例和原理细节");
        }

        return new AnalysisResult(strengths, weaknesses);
    }

    /**
     * 分析结果。
     */
    public record AnalysisResult(List<String> strengths, List<String> weaknesses) {
    }
}
