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
 * 面试出题安全任务使用的 classpath Markdown Skill 加载器。
 *
 * <p>当前进程启动时扫描固定目录，把每份 UTF-8 文档按 frontmatter 或文件名映射到内存
 * EnumMap；InterviewQuestionTaskDefinition 按服务端题型读取全文并拼入 system prompt。
 * 加载 I/O 失败只记录日志，缺失 Skill 会让 Interviewer 使用固定模板。</p>
 */
@Slf4j
@Component
public class SkillsTool {

    /** 当前进程启动时扫描的固定 classpath 资源模式，只包含 skills 根目录下的 Markdown。 */
    private static final String SKILL_LOCATION_PATTERN = "classpath:skills/*.md";

    /** 环节到最后加载文档的进程内映射；重复环节会由后加载资源覆盖先加载资源。 */
    private final Map<InterviewPhase, SkillDocument> skillMap = new EnumMap<>(InterviewPhase.class);

    /**
     * Spring 注入完成后从 classpath 加载全部 Skill 文档。
     * 单个 parse 抛出的 I/O 异常会跳出整个 try 并只记录错误；已放入映射的文档继续保留。
     */
    @PostConstruct
    public void loadSkills() {
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        try {
            // 资源解析和读取都在当前启动线程同步执行，不建立热更新或后续重载。
            Resource[] resources = resolver.getResources(SKILL_LOCATION_PATTERN);
            for (Resource resource : resources) {
                // frontmatter/文件名确定环节后放入内存；同环节后加载文档会覆盖已有值。
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
     * 返回指定环节的内存 Skill 文档；未加载时返回 {@code null}，调用权限仅限 Interviewer。
     */
    @AgentPermission(AgentType.INTERVIEWER)
    public SkillDocument getSkill(InterviewPhase phase) {
        return skillMap.get(phase);
    }

    /**
     * 返回指定环节的完整 Markdown 文本供 system prompt 使用；未加载时返回空字符串。
     */
    @AgentPermission(AgentType.INTERVIEWER)
    public String getSkillPrompt(InterviewPhase phase) {
        SkillDocument doc = skillMap.get(phase);
        return doc == null ? "" : doc.getContent();
    }

    /**
     * 返回当前内存映射值的不可变快照；EnumMap 迭代顺序按环节枚举自然顺序。
     */
    @AgentPermission(AgentType.INTERVIEWER)
    public List<SkillDocument> listSkills() {
        return List.copyOf(skillMap.values());
    }

    /**
     * 判断当前进程内存映射是否包含指定环节；该结果不验证文档内容是否符合业务规则。
     */
    @AgentPermission(AgentType.INTERVIEWER)
    public boolean hasSkill(InterviewPhase phase) {
        return skillMap.containsKey(phase);
    }

    /**
     * 以 UTF-8 读取完整资源，优先解析 triggerPhase，缺失或非法时按文件名兜底，并保留全文。
     */
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
     * 解析 Markdown 开头 frontmatter 中首个 triggerPhase；缺少边界、字段或未知值时返回 null。
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
     * 根据小写文件名关键字推断环节；空文件名或没有命中任何关键字时回退 SELF_INTRO。
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

    /** 启动加载后缓存的一份 Skill 文档。 */
    @Data
    public static class SkillDocument {
        /** frontmatter 或文件名推断出的服务端环节。 */
        private final InterviewPhase phase;
        /** classpath 资源文件名，可能为空。 */
        private final String fileName;
        /** 以 UTF-8 读取的完整 Markdown 内容，包括 frontmatter。 */
        private final String content;

        /** 保存已解析环节、资源名和完整文档内容。 */
        public SkillDocument(InterviewPhase phase, String fileName, String content) {
            this.phase = phase;
            this.fileName = fileName;
            this.content = content;
        }
    }
}
