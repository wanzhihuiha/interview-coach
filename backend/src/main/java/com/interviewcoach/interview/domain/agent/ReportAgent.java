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
 * 根据完整面试问答和岗位画像生成报告文本素材的 Agent。
 *
 * <p>应用服务在报告缓存未命中时调用本组件；当前实现直接使用旧 {@link LlmService}，未经过
 * 面试出题/评估使用的类型化安全网关。模型或 JSON 解析失败时根据问答数量、深度、回答长度
 * 和岗位首项技能生成本地降级优势与薄弱点。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReportAgent {

    /** 直接接收完整岗位要求和问答 prompt 的旧模型服务。 */
    private final LlmService llmService;
    /** 负责把模型返回的自由 JSON 解析为通用 Map。 */
    private final ObjectMapper objectMapper;

    /**
     * 分析完整面试问答和岗位画像，生成具体优势与薄弱点。
     *
     * <p>该结果只提供报告文本素材；综合分和五维分仍由应用服务本地公式生成。任何模型调用、
     * 围栏清理、JSON 字段转换异常都会进入本地降级，不把模型原始失败返回给用户。</p>
     *
     * @param messages 按消息序号读取的完整面试问答记录
     * @param positionProfile 岗位画像
     * @return 包含 strengths 和 weaknesses 的分析结果
     */
    public AnalysisResult analyze(List<InterviewMessage> messages, PositionProfileData positionProfile) {
        return AgentContext.runAs(AgentType.REPORT, () -> {
            // 把完整问题和候选人回答拼进旧 prompt；当前路径没有 DATA_ONLY 隔离。
            String qaText = buildQaText(messages);
            // 岗位必需技能及重要度/深度也直接拼进旧 prompt。
            String skillsText = buildSkillsText(positionProfile);

            String system = "你是一名资深技术面试官，正在根据面试问答记录撰写评估报告。"
                    + "请严格基于候选人的实际回答内容，输出具体、可落地的优势和薄弱点。"
                    + "不要输出抽象评价如'部分问题可进一步深入'。"
                    + "薄弱点必须采用'技术领域：具体问题'的格式，例如'JVM 内存区域：未掌握堆、虚拟机栈、方法区等核心概念'、'项目数据量化：自我介绍缺少 QPS/RT/用户量等可量化指标'。"
                    + "输出合法 JSON，格式：{\"strengths\":[\"具体优势1\",\"具体优势2\"],\"weaknesses\":[\"技术领域1：具体问题1\",\"技术领域2：具体问题2\",\"技术领域3：具体问题3\"]}";
            String user = "岗位核心要求：\n" + skillsText + "\n\n面试问答记录：\n" + qaText
                    + "\n\n请输出具体的优势和薄弱点。薄弱点必须是'技术领域：具体问题'的格式，便于后续生成针对性学习方案。";

            try {
                // 直接调用底层模型服务；该路径不经过 LlmInterviewService 的安全网关和 Redis 限流。
                String json = llmService.chat(system, user);
                String cleaned = json.trim();
                if (cleaned.startsWith("```")) {
                    cleaned = cleaned.replaceAll("^```(json)?\\s*", "").replaceAll("\\s*```$", "").trim();
                }
                // 兼容整体代码围栏后宽松读取 Map；缺失或非列表字段由 extractList 降级为空列表。
                Map<?, ?> map = objectMapper.readValue(cleaned, Map.class);
                List<String> strengths = extractList(map.get("strengths"));
                List<String> weaknesses = extractList(map.get("weaknesses"));
                return new AnalysisResult(strengths, weaknesses);
            } catch (Exception e) {
                log.warn("[ReportAgent] LLM 分析失败，使用兜底分析: {}", e.getMessage());
                // 模型、传输或解析失败都改用确定性本地规则，报告生成不会因该异常终止。
                return fallbackAnalysis(messages, positionProfile);
            }
        });
    }

    /**
     * 按输入顺序拼接双方消息正文；面试官消息额外附带非空主题和当前深度。
     */
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

    /**
     * 把岗位画像全部必需技能拼成多行文本；画像或技能列表为空时返回固定缺省说明。
     */
    private String buildSkillsText(PositionProfileData profile) {
        if (profile == null || profile.getRequiredSkills() == null) {
            return "未提供岗位画像";
        }
        return profile.getRequiredSkills().stream()
                .map(s -> s.getSkill() + "（重要度：" + s.getImportance() + "，深度：" + s.getDepth() + "）")
                .collect(Collectors.joining("\n"));
    }

    /** 将通用 JSON 字段转换为非空字符串列表；非列表字段返回空列表。 */
    @SuppressWarnings("unchecked")
    private List<String> extractList(Object obj) {
        if (obj instanceof List<?> list) {
            return list.stream().map(String::valueOf).filter(s -> !s.isBlank()).toList();
        }
        return new ArrayList<>();
    }

    /**
     * 根据现有问答和岗位首项技能生成本地降级分析。
     *
     * <p>候选人消息超过 2 条才添加持续参与优势；最大题目深度不超过 2、平均回答长度低于 80
     * 或存在岗位首项技能时添加对应薄弱点。2、80 和选择首项技能的精确产品依据缺失。</p>
     */
    private AnalysisResult fallbackAnalysis(List<InterviewMessage> messages, PositionProfileData profile) {
        List<String> strengths = new ArrayList<>();
        List<String> weaknesses = new ArrayList<>();

        int candidateMsgCount = (int) messages.stream().filter(m -> "candidate".equals(m.getRole())).count();
        if (candidateMsgCount > 2) {
            strengths.add("能够持续参与多轮问答，表达意愿较好");
        }

        // 只取岗位必需技能列表第一项生成缺口，不对全部技能评分或排序。
        if (profile != null && profile.getRequiredSkills() != null && !profile.getRequiredSkills().isEmpty()) {
            String topSkill = profile.getRequiredSkills().get(0).getSkill();
            weaknesses.add("对岗位核心技能「" + topSkill + "」的深度掌握情况在面试中体现不足");
        }

        // 只读取消息中已有深度的最大值；没有深度时按 L1 处理。
        int maxDepth = messages.stream().filter(m -> m.getDepth() != null).mapToInt(InterviewMessage::getDepth).max().orElse(1);
        if (maxDepth <= 2) {
            weaknesses.add("技术追问深度停留在 L1-L2，原理机制和实践踩坑类问题回答较少");
        }

        // 按候选人消息正文的 UTF-16 长度计算平均值，空正文按 0 计。
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
     * 报告 Agent 交给应用服务的文本列表结果。
     *
     * @param strengths 模型或本地规则生成的优势列表
     * @param weaknesses 模型或本地规则生成的薄弱点列表
     */
    public record AnalysisResult(List<String> strengths, List<String> weaknesses) {
    }
}
