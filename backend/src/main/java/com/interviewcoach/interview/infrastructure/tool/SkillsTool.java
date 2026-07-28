package com.interviewcoach.interview.infrastructure.tool;

import com.interviewcoach.common.security.agent.AgentPermission;
import com.interviewcoach.common.security.agent.AgentType;
import com.interviewcoach.interview.domain.entity.InterviewPhase;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

/**
 * Skills 工具：加载并管理面试环节的 markdown Skill 编排文档。
 * 协调者 Agent 根据当前环节（意图）获取对应 Skill，注入 LLM prompt。
 */
@Slf4j
@Component
public class SkillsTool {

    private static final String SKILL_LOCATION_PATTERN = "classpath:skills/*.md";

    private final Map<InterviewPhase, SkillDocument> skillMap = new EnumMap<>(InterviewPhase.class);

    /**
     * 初始化时从 classpath 加载所有 Skill 文档。
     */
    @PostConstruct
    public void loadSkills() {
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        try {
            Resource[] resources = resolver.getResources(SKILL_LOCATION_PATTERN);
            for (Resource resource : resources) {
                SkillDocument doc = parse(resource);
                skillMap.put(doc.getPhase(), doc);
                log.info("[SkillsTool] 已加载 Skill: {} -> {}", doc.getPhase(), resource.getFilename());
            }
        } catch (IOException e) {
            log.error("[SkillsTool] 加载 Skill 文档失败", e);
        }

        if (skillMap.isEmpty()) {
            log.warn("[SkillsTool] 未找到任何 Skill 文档，将使用默认策略");
        }
    }

    /**
     * 根据环节获取 Skill 文档。
     */
    @AgentPermission(AgentType.INTERVIEWER)
    public SkillDocument getSkill(InterviewPhase phase) {
        return skillMap.get(phase);
    }

    /**
     * 获取指定环节的完整 Skill prompt 内容。
     */
    @AgentPermission(AgentType.INTERVIEWER)
    public String getSkillPrompt(InterviewPhase phase) {
        SkillDocument doc = skillMap.get(phase);
        return doc == null ? "" : doc.getContent();
    }

    /**
     * 列出所有已加载的 Skill。
     */
    @AgentPermission(AgentType.INTERVIEWER)
    public List<SkillDocument> listSkills() {
        return List.copyOf(skillMap.values());
    }

    /**
     * 判断是否已加载指定环节的 Skill。
     */
    @AgentPermission(AgentType.INTERVIEWER)
    public boolean hasSkill(InterviewPhase phase) {
        return skillMap.containsKey(phase);
    }

    private SkillDocument parse(Resource resource) throws IOException {
        String content = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String fileName = resource.getFilename();
        InterviewPhase phase = parseTriggerPhase(content);
        if (phase == null) {
            phase = inferPhase(fileName);
        }
        return new SkillDocument(phase, fileName, content);
    }

    /**
     * 解析 markdown 文件 frontmatter 中的 triggerPhase。
     */
    private InterviewPhase parseTriggerPhase(String content) {
        if (content == null || !content.startsWith("---")) {
            return null;
        }
        int end = content.indexOf("---", 3);
        if (end < 0) {
            return null;
        }
        String frontmatter = content.substring(0, end);
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("triggerPhase\\s*:\\s*([A-Za-z_]+)")
                .matcher(frontmatter);
        if (matcher.find()) {
            try {
                return InterviewPhase.valueOf(matcher.group(1).trim());
            } catch (IllegalArgumentException e) {
                log.warn("[SkillsTool] 未知的 triggerPhase: {}", matcher.group(1));
            }
        }
        return null;
    }

    /**
     * 根据文件名推断环节（frontmatter 缺失时的兜底）。
     */
    private InterviewPhase inferPhase(String fileName) {
        if (fileName == null) {
            return InterviewPhase.SELF_INTRO;
        }
        String lower = fileName.toLowerCase();
        if (lower.contains("self-intro") || lower.contains("selfintro")) {
            return InterviewPhase.SELF_INTRO;
        }
        if (lower.contains("professional") || lower.contains("profession")) {
            return InterviewPhase.PROFESSIONAL;
        }
        if (lower.contains("resume") || lower.contains("project")) {
            return InterviewPhase.RESUME_DISCUSSION;
        }
        if (lower.contains("behavioral") || lower.contains("behavior") || lower.contains("star")) {
            return InterviewPhase.BEHAVIORAL;
        }
        if (lower.contains("ending") || lower.contains("end")) {
            return InterviewPhase.ENDING;
        }
        return InterviewPhase.SELF_INTRO;
    }

    /**
     * Skill 文档内存表示。
     */
    @Data
    public static class SkillDocument {
        private final InterviewPhase phase;
        private final String fileName;
        private final String content;

        public SkillDocument(InterviewPhase phase, String fileName, String content) {
            this.phase = phase;
            this.fileName = fileName;
            this.content = content;
        }
    }
}
