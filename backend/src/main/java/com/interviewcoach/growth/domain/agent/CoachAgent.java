package com.interviewcoach.growth.domain.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.interviewcoach.common.security.agent.AgentContext;
import com.interviewcoach.common.security.agent.AgentType;
import com.interviewcoach.growth.application.dto.GrowthPlanResponse;
import com.interviewcoach.resume.infrastructure.ai.LlmService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 教练 Agent：把面试报告中的薄弱点整理为可执行的成长方案。
 *
 * <p>上游由 {@code GrowthPlanService} 调用；当前实现不调用大模型，而是根据岗位大类和内置规则、
 * 资源表生成学习路径、练习题和知识缺口，再输出结构化结果与 Markdown 内容。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CoachAgent {

    private final LlmService llmService;
    private final ObjectMapper objectMapper;

    /**
     * 使用通用岗位规则生成成长方案，并从报告文本中提取薄弱点。
     *
     * <p>这是未提供岗位类别和结构化薄弱点时的兼容入口。当前只识别报告中首次出现的单行
     * “劣势：...”内容，并按顿号拆分；没有识别到时由三参数入口补入通用薄弱点。
     * 该解析没有对 {@code null} 报告文本做保护。</p>
     *
     * @param reportText 面试评估报告文本（含分数、维度、优劣势、结论）
     * @return 结构化成长方案
     */
    public GrowthPlanResponse generatePlan(String reportText) {
        return generatePlan(reportText, JobCategory.GENERAL, extractWeaknesses(reportText));
    }

    /**
     * 根据岗位类别和薄弱点，使用本地规则与模板生成成长方案。
     *
     * <p>岗位类别会先归一化；没有有效薄弱点时补入一条默认项。学习路径、练习题和知识缺口都只使用
     * 薄弱点列表的前 3 项，最后汇总为 Markdown。{@code reportText} 参数当前保留但不参与生成；
     * 整个过程仅在教练 Agent 身份上下文中执行，不会调用大模型。</p>
     *
     * @param reportText   面试评估报告文本，当前实现未使用
     * @param jobCategory  岗位大类（如 TECH、PRODUCT、OPERATION）
     * @param weaknesses   具体薄弱点列表
     * @return 结构化成长方案
     */
    public GrowthPlanResponse generatePlan(String reportText, String jobCategory, List<String> weaknesses) {
        return AgentContext.runAs(AgentType.COACH, () -> {
            // 先统一岗位分类，避免上游传入空值或非标准名称导致规则选择不一致。
            String category = JobCategory.normalize(jobCategory);

            List<String> actualWeaknesses = weaknesses;
            if (actualWeaknesses == null || actualWeaknesses.isEmpty()) {
                // 报告没有给出薄弱点时仍生成一条通用改进路径，避免返回空方案。
                actualWeaknesses = List.of(buildDefaultWeakness(category));
            }

            // 三部分内容来自同一组薄弱点，最后再汇总成前端直接展示的 Markdown。
            GrowthPlanResponse response = new GrowthPlanResponse();
            response.setLearningPath(buildLearningPath(actualWeaknesses, category));
            response.setExercises(buildExercises(actualWeaknesses, category));
            response.setKnowledgeGaps(buildKnowledgeGaps(actualWeaknesses, category));
            response.setMdContent(buildMarkdown(response));

            return response;
        });
    }

    private String buildDefaultWeakness(String category) {
        if (JobCategory.isTechnical(category)) {
            return "岗位核心技能实践：建议结合岗位画像补充具体项目案例和原理细节";
        }
        return "岗位核心能力实践：建议结合目标岗位补充具体场景案例和实战经验";
    }

    /**
     * 从报告文本首次出现的单行“劣势：...”中提取薄弱点。
     *
     * <p>匹配后会移除方括号并按中文顿号拆分；当前不会解析“薄弱知识点”章节、项目符号、
     * 多行列表或 JSON。没有匹配时返回空列表，由上层补入默认薄弱点。</p>
     */
    private List<String> extractWeaknesses(String reportText) {
        List<String> result = new ArrayList<>();
        Pattern pattern = Pattern.compile("劣势[：:]\\s*(.+?)(?:\\n|$)");
        Matcher matcher = pattern.matcher(reportText);
        if (matcher.find()) {
            String line = matcher.group(1).trim();
            if (line.startsWith("[")) {
                line = line.replace("[", "").replace("]", "");
            }
            for (String item : line.split("、")) {
                String trimmed = item.trim();
                if (!trimmed.isEmpty()) {
                    result.add(trimmed);
                }
            }
        }
        return result;
    }

    /**
     * 按薄弱点顺序生成最多 3 个学习阶段，时长依次固定为 1 周、1-2 周和 2-3 周。
     */
    private List<GrowthPlanResponse.LearningPathItem> buildLearningPath(List<String> weaknesses, String category) {
        List<GrowthPlanResponse.LearningPathItem> paths = new ArrayList<>();
        String[] durations = {"1 周", "1-2 周", "2-3 周"};
        for (int i = 0; i < Math.min(weaknesses.size(), 3); i++) {
            GrowthPlanResponse.LearningPathItem item = new GrowthPlanResponse.LearningPathItem();
            item.setPhase("阶段 " + (i + 1) + "：攻克 " + weaknesses.get(i));
            item.setDuration(durations[i % durations.length]);
            item.setGoal(buildGoal(weaknesses.get(i), category));
            item.setTasks(buildTasks(weaknesses.get(i), category));
            item.setResources(buildResources(weaknesses.get(i), category));
            paths.add(item);
        }
        return paths;
    }

    private String buildGoal(String weakness, String category) {
        if (JobCategory.isTechnical(category)) {
            return "能够系统回答 " + weakness + " 相关面试题，并给出实践案例";
        }
        return "能够在 " + weakness + " 相关场景下给出结构化分析、可落地的方案并辅以案例说明";
    }

    private List<String> buildTasks(String weakness, String category) {
        if (JobCategory.isTechnical(category)) {
            return List.of(
                    "复习 " + weakness + " 的核心概念与原理",
                    "阅读 2-3 篇高质量技术文章或官方文档",
                    "手写一个最小实践 Demo 加深理解",
                    "整理该知识点的常见面试题与答案"
            );
        }
        return List.of(
                "梳理 " + weakness + " 的核心方法论与关键步骤",
                "研读 2-3 个同行业标杆案例",
                "结合目标岗位模拟一个真实场景并输出方案",
                "准备 3-5 个可复用的面试表达要点"
        );
    }

    /**
     * 为单个薄弱点匹配内置学习资源。
     *
     * <p>先取中英文冒号前的主题，在对应岗位资源表中精确匹配；没有结果时再按岗位类别执行关键词匹配，
     * 两次都未命中则返回空资源列表。</p>
     */
    private List<GrowthPlanResponse.LearningPathItem.Resource> buildResources(String weakness, String category) {
        String topic = extractTopic(weakness);
        List<ResourceEntry> entries = resourceMapFor(category).getOrDefault(topic, List.of());
        if (entries.isEmpty()) {
            entries = matchByKeyword(weakness, category);
        }
        if (entries.isEmpty()) {
            return List.of();
        }

        List<GrowthPlanResponse.LearningPathItem.Resource> resources = new ArrayList<>();
        for (ResourceEntry entry : entries) {
            GrowthPlanResponse.LearningPathItem.Resource r = new GrowthPlanResponse.LearningPathItem.Resource();
            r.setName(entry.name);
            r.setType(entry.type);
            r.setUrl(entry.url);
            resources.add(r);
        }
        return resources;
    }

    private List<ResourceEntry> matchByKeyword(String weakness, String category) {
        String lower = weakness.toLowerCase();
        if (JobCategory.isTechnical(category)) {
            return matchTechnicalKeyword(lower);
        }
        if (JobCategory.PRODUCT.equalsIgnoreCase(category)) {
            return matchProductKeyword(lower);
        }
        if (JobCategory.DESIGN.equalsIgnoreCase(category)) {
            return matchDesignKeyword(lower);
        }
        if (JobCategory.OPERATION.equalsIgnoreCase(category)) {
            return matchOperationKeyword(lower);
        }
        if (JobCategory.SALES.equalsIgnoreCase(category)) {
            return matchSalesKeyword(lower);
        }
        return List.of();
    }

    private List<ResourceEntry> matchTechnicalKeyword(String lower) {
        if (containsAny(lower, "jvm")) {
            return TECHNICAL_RESOURCES.get("JVM 内存区域");
        }
        if (containsAny(lower, "java并发", "并发编程", "并发控制", "多线程", "线程池",
                "volatile", "synchronized", "锁机制", "cas", "aqs")) {
            return TECHNICAL_RESOURCES.get("Java 并发");
        }
        if (containsAny(lower, "spring")) {
            return TECHNICAL_RESOURCES.get("Spring Boot");
        }
        if (containsAny(lower, "mysql")) {
            return TECHNICAL_RESOURCES.get("MySQL");
        }
        if (containsAny(lower, "redis")) {
            return TECHNICAL_RESOURCES.get("Redis");
        }
        if (containsAny(lower, "kafka")) {
            return TECHNICAL_RESOURCES.get("Kafka");
        }
        if (containsAny(lower, "rabbitmq")) {
            return TECHNICAL_RESOURCES.get("RabbitMQ");
        }
        if (containsAny(lower, "kubernetes", "k8s")) {
            return TECHNICAL_RESOURCES.get("Kubernetes");
        }
        if (containsAny(lower, "docker")) {
            return TECHNICAL_RESOURCES.get("Docker");
        }
        if (containsAny(lower, "消息队列")) {
            return TECHNICAL_RESOURCES.get("消息队列");
        }
        if (containsAny(lower, "云原生")) {
            return TECHNICAL_RESOURCES.get("云原生");
        }
        return List.of();
    }

    private List<ResourceEntry> matchProductKeyword(String lower) {
        if (containsAny(lower, "需求", "prd", "需求分析")) {
            return PRODUCT_RESOURCES.get("需求分析");
        }
        if (containsAny(lower, "数据", "指标", "数据分析")) {
            return PRODUCT_RESOURCES.get("数据分析");
        }
        if (containsAny(lower, "用户", "用户研究", "画像")) {
            return PRODUCT_RESOURCES.get("用户研究");
        }
        if (containsAny(lower, "项目管理", "敏捷", "scrum")) {
            return PRODUCT_RESOURCES.get("项目管理");
        }
        return List.of();
    }

    private List<ResourceEntry> matchDesignKeyword(String lower) {
        if (containsAny(lower, "交互", "用户体验", "ux")) {
            return DESIGN_RESOURCES.get("用户体验");
        }
        if (containsAny(lower, "视觉", "色彩", "排版")) {
            return DESIGN_RESOURCES.get("视觉设计");
        }
        return List.of();
    }

    private List<ResourceEntry> matchOperationKeyword(String lower) {
        if (containsAny(lower, "增长", "获客", "裂变")) {
            return OPERATION_RESOURCES.get("用户增长");
        }
        if (containsAny(lower, "内容", "文案", "自媒体")) {
            return OPERATION_RESOURCES.get("内容运营");
        }
        if (containsAny(lower, "活动", "策划")) {
            return OPERATION_RESOURCES.get("活动策划");
        }
        return List.of();
    }

    private List<ResourceEntry> matchSalesKeyword(String lower) {
        if (containsAny(lower, "销售", "谈判", "成交")) {
            return SALES_RESOURCES.get("销售方法");
        }
        if (containsAny(lower, "客户", "关系", "维护")) {
            return SALES_RESOURCES.get("客户成功");
        }
        return List.of();
    }

    private Map<String, List<ResourceEntry>> resourceMapFor(String category) {
        return switch (JobCategory.normalize(category)) {
            case JobCategory.PRODUCT -> PRODUCT_RESOURCES;
            case JobCategory.DESIGN -> DESIGN_RESOURCES;
            case JobCategory.OPERATION -> OPERATION_RESOURCES;
            case JobCategory.SALES -> SALES_RESOURCES;
            case JobCategory.TECH -> TECHNICAL_RESOURCES;
            default -> GENERAL_RESOURCES;
        };
    }

    private boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private String extractTopic(String weakness) {
        if (weakness == null) {
            return "";
        }
        String normalized = weakness.trim();
        int colonIndex = normalized.indexOf("：");
        if (colonIndex > 0) {
            return normalized.substring(0, colonIndex).trim();
        }
        int asciiColonIndex = normalized.indexOf(":");
        if (asciiColonIndex > 0) {
            return normalized.substring(0, asciiColonIndex).trim();
        }
        return normalized;
    }

    private record ResourceEntry(String name, String type, String url) {
    }

    // CHECKSTYLE:OFF
    private static final Map<String, List<ResourceEntry>> TECHNICAL_RESOURCES = Map.ofEntries(
            Map.entry("JVM 内存区域", List.of(
                    new ResourceEntry("The Java Virtual Machine Specification - Run-Time Data Areas", "文档", "https://docs.oracle.com/javase/specs/jvms/se21/html/jvms-2.html#jvms-2.5"),
                    new ResourceEntry("Oracle Java HotSpot GC Tuning Guide", "文档", "https://docs.oracle.com/en/java/javase/21/gctuning/")
            )),
            Map.entry("JVM 垃圾回收", List.of(
                    new ResourceEntry("Oracle Java HotSpot Garbage Collection Tuning Guide", "文档", "https://docs.oracle.com/en/java/javase/21/gctuning/"),
                    new ResourceEntry("OpenJDK Garbage-First Garbage Collector", "文档", "https://openjdk.org/projects/jdk/21/")
            )),
            Map.entry("Java 并发", List.of(
                    new ResourceEntry("Oracle Java Concurrency Tutorial", "文档", "https://docs.oracle.com/javase/tutorial/essential/concurrency/"),
                    new ResourceEntry("Java Memory Model FAQ", "文档", "https://www.cs.umd.edu/~pugh/java/memoryModel/")
            )),
            Map.entry("volatile", List.of(
                    new ResourceEntry("Oracle Java Concurrency Tutorial - Atomic Variables", "文档", "https://docs.oracle.com/javase/tutorial/essential/concurrency/atomicvars.html")
            )),
            Map.entry("synchronized", List.of(
                    new ResourceEntry("Oracle Java Concurrency Tutorial - Synchronization", "文档", "https://docs.oracle.com/javase/tutorial/essential/concurrency/sync.html")
            )),
            Map.entry("Spring Boot", List.of(
                    new ResourceEntry("Spring Boot Reference Documentation", "文档", "https://docs.spring.io/spring-boot/docs/current/reference/htmlsingle/")
            )),
            Map.entry("MySQL", List.of(
                    new ResourceEntry("MySQL 8.4 Reference Manual", "文档", "https://dev.mysql.com/doc/refman/8.4/en/")
            )),
            Map.entry("Redis", List.of(
                    new ResourceEntry("Redis Documentation", "文档", "https://redis.io/docs/latest/")
            )),
            Map.entry("Kafka", List.of(
                    new ResourceEntry("Apache Kafka Documentation", "文档", "https://kafka.apache.org/documentation/")
            )),
            Map.entry("RabbitMQ", List.of(
                    new ResourceEntry("RabbitMQ Documentation", "文档", "https://www.rabbitmq.com/docs")
            )),
            Map.entry("Kubernetes", List.of(
                    new ResourceEntry("Kubernetes Documentation", "文档", "https://kubernetes.io/docs/home/")
            )),
            Map.entry("云原生", List.of(
                    new ResourceEntry("CNCF Cloud Native Definition", "文档", "https://www.cncf.io/about/who-we-are/")
            )),
            Map.entry("Docker", List.of(
                    new ResourceEntry("Docker Documentation", "文档", "https://docs.docker.com/")
            )),
            Map.entry("消息队列", List.of(
                    new ResourceEntry("Apache Kafka Documentation", "文档", "https://kafka.apache.org/documentation/")
            ))
    );

    private static final Map<String, List<ResourceEntry>> PRODUCT_RESOURCES = Map.ofEntries(
            Map.entry("需求分析", List.of(
                    new ResourceEntry("Mind the Product - Product Management Resources", "文档", "https://www.mindtheproduct.com/resources/")
            )),
            Map.entry("数据分析", List.of(
                    new ResourceEntry("Google Analytics Help Center", "文档", "https://support.google.com/analytics/")
            )),
            Map.entry("用户研究", List.of(
                    new ResourceEntry("Nielsen Norman Group - User Research", "文档", "https://www.nngroup.com/topic/user-research/")
            )),
            Map.entry("项目管理", List.of(
                    new ResourceEntry("Scrum.org Resources", "文档", "https://www.scrum.org/resources")
            ))
    );

    private static final Map<String, List<ResourceEntry>> DESIGN_RESOURCES = Map.ofEntries(
            Map.entry("用户体验", List.of(
                    new ResourceEntry("Nielsen Norman Group - UX Design", "文档", "https://www.nngroup.com/topic/ux-design/")
            )),
            Map.entry("视觉设计", List.of(
                    new ResourceEntry("Google Material Design", "文档", "https://m3.material.io/")
            ))
    );

    private static final Map<String, List<ResourceEntry>> OPERATION_RESOURCES = Map.ofEntries(
            Map.entry("用户增长", List.of(
                    new ResourceEntry("GrowthHackers - Growth Studies", "文档", "https://growthhackers.com/growth-studies")
            )),
            Map.entry("内容运营", List.of(
                    new ResourceEntry("Content Marketing Institute", "文档", "https://contentmarketinginstitute.com/")
            )),
            Map.entry("活动策划", List.of(
                    new ResourceEntry("Event Marketing Guide - HubSpot", "文档", "https://www.hubspot.com/event-marketing")
            ))
    );

    private static final Map<String, List<ResourceEntry>> SALES_RESOURCES = Map.ofEntries(
            Map.entry("销售方法", List.of(
                    new ResourceEntry("HubSpot Sales Blog", "文档", "https://www.hubspot.com/sales")
            )),
            Map.entry("客户成功", List.of(
                    new ResourceEntry("Gainsight Customer Success Resources", "文档", "https://www.gainsight.com/resources/")
            ))
    );

    private static final Map<String, List<ResourceEntry>> GENERAL_RESOURCES = Map.ofEntries(
            Map.entry("沟通表达", List.of(
                    new ResourceEntry("MindTools Communication Skills", "文档", "https://www.mindtools.com/page8.html")
            ))
    );
    // CHECKSTYLE:ON

    /**
     * 根据前 3 个薄弱点生成练习。
     * 技术岗依次使用概念问答、方案设计和代码实践，其他岗位依次使用场景分析、方案设计和模拟演练。
     */
    private List<GrowthPlanResponse.Exercise> buildExercises(List<String> weaknesses, String category) {
        List<GrowthPlanResponse.Exercise> exercises = new ArrayList<>();
        String[] types = exerciseTypesFor(category);
        for (int i = 0; i < Math.min(weaknesses.size(), 3); i++) {
            GrowthPlanResponse.Exercise exercise = new GrowthPlanResponse.Exercise();
            String type = types[i % types.length];
            exercise.setTitle("练习 " + (i + 1) + "：" + type + " - " + weaknesses.get(i));
            exercise.setContent(buildExerciseContent(type, weaknesses.get(i), category));
            exercises.add(exercise);
        }
        return exercises;
    }

    private String[] exerciseTypesFor(String category) {
        if (JobCategory.isTechnical(category)) {
            return new String[]{"概念问答", "方案设计", "代码实践"};
        }
        return new String[]{"场景分析", "方案设计", "模拟演练"};
    }

    private String buildExerciseContent(String type, String weakness, String category) {
        if (JobCategory.isTechnical(category)) {
            return switch (type) {
                case "概念问答" -> "请用 200 字以内回答：「" + weakness
                        + "」的核心概念是什么？它解决了什么问题？常见的使用场景有哪些？";
                case "方案设计" -> "请设计一个基于「" + weakness
                        + "」的技术方案：描述背景、关键设计点、可能的风险和优化方向。";
                default -> "请手写一段与「" + weakness
                        + "」相关的最小代码示例，并说明每一行关键代码的作用。";
            };
        }
        return switch (type) {
            case "场景分析" -> "请描述一个你曾遇到的「" + weakness
                    + "」真实场景：背景、你的角色、当时采取了哪些行动、结果如何？";
            case "方案设计" -> "针对目标岗位的一个典型业务场景，请设计一份可落地的「" + weakness
                    + "」方案，包含目标、关键步骤、衡量指标和风险预案。";
            default -> "请模拟一次面试或工作汇报：用 2-3 分钟结构化表达你对「" + weakness
                    + "」的理解，并准备应对 3 个可能的追问。";
        };
    }

    /**
     * 根据前 3 个薄弱点生成知识缺口。
     * 第一项重要度固定为高，其余固定为中；关键词只按岗位类别使用固定模板。
     */
    private List<GrowthPlanResponse.KnowledgeGap> buildKnowledgeGaps(List<String> weaknesses, String category) {
        List<GrowthPlanResponse.KnowledgeGap> gaps = new ArrayList<>();
        for (int i = 0; i < Math.min(weaknesses.size(), 3); i++) {
            GrowthPlanResponse.KnowledgeGap gap = new GrowthPlanResponse.KnowledgeGap();
            String weakness = weaknesses.get(i);
            gap.setTopic(weakness);
            gap.setDescription(buildGapDescription(weakness, category));
            gap.setImportance(i == 0 ? "高" : "中");
            gap.setKeywords(keywordsFor(category));
            gaps.add(gap);
        }
        return gaps;
    }

    private String buildGapDescription(String weakness, String category) {
        if (JobCategory.isTechnical(category)) {
            return "面试中针对「" + weakness
                    + "」的回答未能体现出系统性理解，建议从核心概念、典型方案、实践踩坑三个层面补全。";
        }
        return "面试中针对「" + weakness
                + "」的回答未能体现出场景化思考和可落地的方法论，建议从核心方法、标杆案例、实战演练三个层面补全。";
    }

    private List<String> keywordsFor(String category) {
        if (JobCategory.isTechnical(category)) {
            return List.of("核心概念", "常见方案", "实践案例", "面试题");
        }
        return List.of("核心方法", "标杆案例", "实战演练", "面试表达");
    }

    /**
     * 将结构化方案依次渲染为学习路径、知识补全和练习题三部分 Markdown。
     * 推荐资源只输出名称和链接，不输出资源类型；当前不会对动态文本做额外 Markdown 转义。
     */
    private String buildMarkdown(GrowthPlanResponse plan) {
        StringBuilder md = new StringBuilder();
        md.append("# 成长方案\n\n");
        md.append("## 一、学习路径\n\n");
        for (GrowthPlanResponse.LearningPathItem item : plan.getLearningPath()) {
            md.append("### ").append(item.getPhase()).append("\n");
            md.append("- 预计时长：").append(item.getDuration()).append("\n");
            md.append("- 阶段目标：").append(item.getGoal()).append("\n");
            md.append("- 学习任务：\n");
            for (String task : item.getTasks()) {
                md.append("  - ").append(task).append("\n");
            }
            if (item.getResources() != null && !item.getResources().isEmpty()) {
                md.append("- 推荐资源：\n");
                for (GrowthPlanResponse.LearningPathItem.Resource r : item.getResources()) {
                    md.append("  - [").append(r.getName()).append("](").append(r.getUrl()).append(")\n");
                }
            }
            md.append("\n");
        }
        md.append("## 二、知识补全\n\n");
        for (GrowthPlanResponse.KnowledgeGap gap : plan.getKnowledgeGaps()) {
            md.append("### ").append(gap.getTopic()).append("（重要度：").append(gap.getImportance()).append("）\n\n");
            md.append(gap.getDescription()).append("\n\n");
            md.append("关键词：").append(String.join("、", gap.getKeywords())).append("\n\n");
        }
        md.append("## 三、练习题\n\n");
        for (GrowthPlanResponse.Exercise ex : plan.getExercises()) {
            md.append("### ").append(ex.getTitle()).append("\n\n").append(ex.getContent()).append("\n\n");
        }
        return md.toString();
    }
}
