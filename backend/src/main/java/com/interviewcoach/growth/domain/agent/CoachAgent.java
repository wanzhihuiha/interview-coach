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
 * 使用固定本地模板组装成长方案的教练组件。
 *
 * <p>成长应用服务传入岗位类别和报告中的薄弱点，本组件在 COACH Agent 上下文中选择对应的
 * 学习任务、练习模板和内置资源，再生成结构化响应与 Markdown。当前实现不调用大模型；
 * 三参数入口也不读取报告文本，因此结果只由岗位类别、薄弱点和本类固定表决定。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CoachAgent {

    /**
     * 由 Spring 注入的旧模型服务依赖；当前类没有读取该字段，也不会发起模型或外部请求。
     */
    private final LlmService llmService;

    /**
     * 由 Spring 注入的 JSON 转换器；当前类没有读取该字段，结构化 JSON 由应用服务负责生成。
     */
    private final ObjectMapper objectMapper;

    /**
     * 从报告文本中解析“劣势”行，再按通用岗位类别调用本地三参数组装入口。
     *
     * @param reportText 面试报告文本；仅本重载用它提取薄弱点，不会发送给模型
     * @return 按解析到的薄弱点组装的结构化成长方案；未解析到时使用通用默认薄弱点
     */
    public GrowthPlanResponse generatePlan(String reportText) {
        // 先从本地文本提取薄弱点，再复用通用类别的固定方案组装流程。
        return generatePlan(reportText, JobCategory.GENERAL, extractWeaknesses(reportText));
    }

    /**
     * 根据岗位类别和已解析的薄弱点列表组装固定成长方案。
     *
     * <p>{@code reportText} 当前未被读取，仅为旧调用契约保留。类别会先归一化；薄弱点为空时
     * 补一个本地默认项，然后只处理列表前 3 项。最多 3 项以及各阶段时长、任务数量、练习字数、
     * 资源选择和重要度均为当前固定规则，其精确产品依据缺失；调整会直接改变响应与 Markdown。</p>
     *
     * @param reportText 保留的报告文本参数，当前实现不读取也不发送到外部
     * @param jobCategory 面试岗位快照中的类别编码或可兼容识别的类别文本
     * @param weaknesses 报告服务提供的薄弱点列表；null 或空列表会使用一个默认薄弱点
     * @return 同时包含结构化学习材料和 Markdown 的成长方案；主键与岗位标题由应用服务回填
     */
    public GrowthPlanResponse generatePlan(String reportText, String jobCategory, List<String> weaknesses) {
        // 以 COACH 身份包裹整段本地组装，结束后由 AgentContext 恢复调用线程原有上下文。
        return AgentContext.runAs(AgentType.COACH, () -> {
            // 统一类别编码，决定后续技术/非技术模板及固定资源表。
            String category = JobCategory.normalize(jobCategory);

            List<String> actualWeaknesses = weaknesses;
            if (actualWeaknesses == null || actualWeaknesses.isEmpty()) {
                // 没有报告薄弱点时补一个类别相关的固定描述，确保三类输出均至少有一项。
                actualWeaknesses = List.of(buildDefaultWeakness(category));
            }

            GrowthPlanResponse response = new GrowthPlanResponse();
            // 三组结构均按同一薄弱点顺序组装，且各自最多消费前三项。
            response.setLearningPath(buildLearningPath(actualWeaknesses, category));
            response.setExercises(buildExercises(actualWeaknesses, category));
            response.setKnowledgeGaps(buildKnowledgeGaps(actualWeaknesses, category));
            // 最后从已组装的结构化列表渲染 Markdown，保证两个表达共享同一批内容。
            response.setMdContent(buildMarkdown(response));

            return response;
        });
    }

    /** 根据技术类与其他类别返回固定兜底薄弱点，不读取报告或岗位画像正文。 */
    private String buildDefaultWeakness(String category) {
        if (JobCategory.isTechnical(category)) {
            return "岗位核心技能实践：建议结合岗位画像补充具体项目案例和原理细节";
        }
        return "岗位核心能力实践：建议结合目标岗位补充具体场景案例和实战经验";
    }

    /**
     * 从本地报告文本的首个“劣势：”行提取以顿号分隔的条目；未匹配时返回空列表。
     *
     * <p>可选方括号会被直接移除；本方法不理解 JSON、逗号分隔或多行劣势，也不调用模型。</p>
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
     * 按输入顺序把最多前三个薄弱点转换为学习阶段，并依次使用三段固定时长文本。
     * 最多 3 项及“1 周/1-2 周/2-3 周”的精确依据缺失；调整会改变方案规模和学习周期展示。
     */
    private List<GrowthPlanResponse.LearningPathItem> buildLearningPath(List<String> weaknesses, String category) {
        List<GrowthPlanResponse.LearningPathItem> paths = new ArrayList<>();
        String[] durations = {"1 周", "1-2 周", "2-3 周"};
        for (int i = 0; i < Math.min(weaknesses.size(), 3); i++) {
            GrowthPlanResponse.LearningPathItem item = new GrowthPlanResponse.LearningPathItem();
            item.setPhase("阶段 " + (i + 1) + "：攻克 " + weaknesses.get(i));
            item.setDuration(durations[i % durations.length]);
            // 对同一薄弱点依次组装目标、固定任务和可选资源；资源未命中只留下空列表。
            item.setGoal(buildGoal(weaknesses.get(i), category));
            item.setTasks(buildTasks(weaknesses.get(i), category));
            item.setResources(buildResources(weaknesses.get(i), category));
            paths.add(item);
        }
        return paths;
    }

    /** 按技术类或非技术类固定模板生成阶段目标。 */
    private String buildGoal(String weakness, String category) {
        if (JobCategory.isTechnical(category)) {
            return "能够系统回答 " + weakness + " 相关面试题，并给出实践案例";
        }
        return "能够在 " + weakness + " 相关场景下给出结构化分析、可落地的方案并辅以案例说明";
    }

    /**
     * 为每个阶段返回四条固定任务；四条以及“2-3 篇”“3-5 个”的精确依据缺失，
     * 调整会改变用户看到的学习工作量。
     */
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
     * 先用冒号前主题对类别资源表做精确匹配，再按弱点关键词兜底；均未命中时返回空列表。
     * 固定链接、主题映射和关键词选择的产品依据缺失，资源是否可访问不在运行时校验。
     */
    private List<GrowthPlanResponse.LearningPathItem.Resource> buildResources(String weakness, String category) {
        // 先截取冒号前主题，完整主题可直接命中对应类别的固定资源表。
        String topic = extractTopic(weakness);
        // 先在归一化类别对应的固定资源表中按主题精确查找。
        List<ResourceEntry> entries = resourceMapFor(category).getOrDefault(topic, List.of());
        if (entries.isEmpty()) {
            // 精确主题未命中时才按类别使用固定关键词顺序寻找第一组资源。
            entries = matchByKeyword(weakness, category);
        }
        if (entries.isEmpty()) {
            return List.of();
        }

        List<GrowthPlanResponse.LearningPathItem.Resource> resources = new ArrayList<>();
        // 将内部不可变资源条目映射为跨应用层返回的 DTO，不发起网络访问。
        for (ResourceEntry entry : entries) {
            GrowthPlanResponse.LearningPathItem.Resource r = new GrowthPlanResponse.LearningPathItem.Resource();
            r.setName(entry.name);
            r.setType(entry.type);
            r.setUrl(entry.url);
            resources.add(r);
        }
        return resources;
    }

    /** 按技术、产品、设计、运营、销售的类别分支选择关键词匹配器，通用类别不做兜底推荐。 */
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

    /** 按固定优先级返回首个命中的技术主题资源；未命中返回空列表。 */
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

    /** 按需求、数据、用户、项目管理顺序返回首个命中的产品主题资源。 */
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

    /** 按用户体验、视觉设计顺序返回首个命中的设计主题资源。 */
    private List<ResourceEntry> matchDesignKeyword(String lower) {
        if (containsAny(lower, "交互", "用户体验", "ux")) {
            return DESIGN_RESOURCES.get("用户体验");
        }
        if (containsAny(lower, "视觉", "色彩", "排版")) {
            return DESIGN_RESOURCES.get("视觉设计");
        }
        return List.of();
    }

    /** 按用户增长、内容运营、活动策划顺序返回首个命中的运营主题资源。 */
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

    /** 按销售方法、客户成功顺序返回首个命中的销售主题资源。 */
    private List<ResourceEntry> matchSalesKeyword(String lower) {
        if (containsAny(lower, "销售", "谈判", "成交")) {
            return SALES_RESOURCES.get("销售方法");
        }
        if (containsAny(lower, "客户", "关系", "维护")) {
            return SALES_RESOURCES.get("客户成功");
        }
        return List.of();
    }

    /** 根据归一化类别选择固定资源表；未知类别使用仅含通用主题的资源表。 */
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

    /** 判断文本是否包含任一固定关键词，不执行分词、边界或同义词扩展。 */
    private boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 提取薄弱点中首个中文或英文冒号之前的文本作为资源精确匹配主题；null 返回空串。
     */
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

    /**
     * 内置学习资源的一条不可变记录，只用于映射响应资源。
     *
     * @param name 展示给用户的资源名称
     * @param type 资源类型文本，当前固定表均为“文档”
     * @param url 不经运行时可用性校验的外部访问地址
     */
    private record ResourceEntry(String name, String type, String url) {
    }

    // CHECKSTYLE:OFF
    /**
     * 技术类薄弱点主题到固定外部文档的映射；链接与条目选择依据缺失，修改会改变推荐结果。
     */
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

    /**
     * 产品类薄弱点主题到固定外部文档的映射；链接与条目选择依据缺失，修改会改变推荐结果。
     */
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

    /**
     * 设计类薄弱点主题到固定外部文档的映射；链接与条目选择依据缺失，修改会改变推荐结果。
     */
    private static final Map<String, List<ResourceEntry>> DESIGN_RESOURCES = Map.ofEntries(
            Map.entry("用户体验", List.of(
                    new ResourceEntry("Nielsen Norman Group - UX Design", "文档", "https://www.nngroup.com/topic/ux-design/")
            )),
            Map.entry("视觉设计", List.of(
                    new ResourceEntry("Google Material Design", "文档", "https://m3.material.io/")
            ))
    );

    /**
     * 运营类薄弱点主题到固定外部文档的映射；链接与条目选择依据缺失，修改会改变推荐结果。
     */
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

    /**
     * 销售类薄弱点主题到固定外部文档的映射；链接与条目选择依据缺失，修改会改变推荐结果。
     */
    private static final Map<String, List<ResourceEntry>> SALES_RESOURCES = Map.ofEntries(
            Map.entry("销售方法", List.of(
                    new ResourceEntry("HubSpot Sales Blog", "文档", "https://www.hubspot.com/sales")
            )),
            Map.entry("客户成功", List.of(
                    new ResourceEntry("Gainsight Customer Success Resources", "文档", "https://www.gainsight.com/resources/")
            ))
    );

    /**
     * 通用类别的固定资源映射，目前只含“沟通表达”；条目选择依据缺失且无关键词兜底。
     */
    private static final Map<String, List<ResourceEntry>> GENERAL_RESOURCES = Map.ofEntries(
            Map.entry("沟通表达", List.of(
                    new ResourceEntry("MindTools Communication Skills", "文档", "https://www.mindtools.com/page8.html")
            ))
    );
    // CHECKSTYLE:ON

    /**
     * 按薄弱点顺序生成最多三道练习，并循环使用类别对应的三种固定练习类型。
     * 最多 3 项和类型顺序的精确产品依据缺失；调整会改变练习数量、标题和内容模板。
     */
    private List<GrowthPlanResponse.Exercise> buildExercises(List<String> weaknesses, String category) {
        List<GrowthPlanResponse.Exercise> exercises = new ArrayList<>();
        // 先按类别选择固定类型序列，随后按薄弱点索引循环使用。
        String[] types = exerciseTypesFor(category);
        for (int i = 0; i < Math.min(weaknesses.size(), 3); i++) {
            GrowthPlanResponse.Exercise exercise = new GrowthPlanResponse.Exercise();
            String type = types[i % types.length];
            exercise.setTitle("练习 " + (i + 1) + "：" + type + " - " + weaknesses.get(i));
            // 题干只由练习类型、薄弱点和类别填入本地模板，不读取其他报告内容。
            exercise.setContent(buildExerciseContent(type, weaknesses.get(i), category));
            exercises.add(exercise);
        }
        return exercises;
    }

    /**
     * 技术类返回问答、方案、代码三类练习，其他类别返回场景、方案、演练三类练习。
     * 三种类型及其顺序的精确依据缺失，调整会改变练习标题和所选题干模板。
     */
    private String[] exerciseTypesFor(String category) {
        if (JobCategory.isTechnical(category)) {
            return new String[]{"概念问答", "方案设计", "代码实践"};
        }
        return new String[]{"场景分析", "方案设计", "模拟演练"};
    }

    /**
     * 将练习类型和薄弱点填入固定题干模板。
     * “200 字”“2-3 分钟”“3 个追问”等数量限制的精确依据缺失，调整会改变用户练习要求。
     */
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
     * 按薄弱点顺序生成最多三个知识缺口；首项重要度为“高”，其余为“中”。
     * 最多 3 项及该重要度分配的精确产品依据缺失，调整会改变页面排序提示和 Markdown 文本。
     */
    private List<GrowthPlanResponse.KnowledgeGap> buildKnowledgeGaps(List<String> weaknesses, String category) {
        List<GrowthPlanResponse.KnowledgeGap> gaps = new ArrayList<>();
        for (int i = 0; i < Math.min(weaknesses.size(), 3); i++) {
            GrowthPlanResponse.KnowledgeGap gap = new GrowthPlanResponse.KnowledgeGap();
            String weakness = weaknesses.get(i);
            gap.setTopic(weakness);
            // 说明和关键词均按类别使用固定模板，不对薄弱点正文做进一步事实分析。
            gap.setDescription(buildGapDescription(weakness, category));
            gap.setImportance(i == 0 ? "高" : "中");
            gap.setKeywords(keywordsFor(category));
            gaps.add(gap);
        }
        return gaps;
    }

    /** 按技术类或非技术类固定模板生成知识补全说明。 */
    private String buildGapDescription(String weakness, String category) {
        if (JobCategory.isTechnical(category)) {
            return "面试中针对「" + weakness
                    + "」的回答未能体现出系统性理解，建议从核心概念、典型方案、实践踩坑三个层面补全。";
        }
        return "面试中针对「" + weakness
                + "」的回答未能体现出场景化思考和可落地的方法论，建议从核心方法、标杆案例、实战演练三个层面补全。";
    }

    /**
     * 按技术类或非技术类返回四个固定复习关键词；数量和词项的精确依据缺失，
     * 调整会改变结构化响应和 Markdown 的检索提示。
     */
    private List<String> keywordsFor(String category) {
        if (JobCategory.isTechnical(category)) {
            return List.of("核心概念", "常见方案", "实践案例", "面试题");
        }
        return List.of("核心方法", "标杆案例", "实战演练", "面试表达");
    }

    /**
     * 按学习路径、知识补全、练习题的固定顺序把结构化响应渲染为 Markdown。
     *
     * <p>资源为空时省略推荐资源段；方法假定三个列表及其必需字段已由本类完成组装，
     * 不做 HTML 转义、链接校验或空列表降级。</p>
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
