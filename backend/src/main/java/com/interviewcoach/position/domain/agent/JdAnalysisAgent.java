package com.interviewcoach.position.domain.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.interviewcoach.common.security.agent.AgentContext;
import com.interviewcoach.common.security.agent.AgentType;
import com.interviewcoach.position.domain.model.PositionProfileData;
import com.interviewcoach.resume.infrastructure.ai.LlmService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 岗位解析流程中的画像生成组件。
 *
 * <p>上游 {@code PositionService} 传入 JD 正文和岗位大类；本组件结合类内硬编码的岗位参考内容调用 {@link LlmService}
 * 生成结构化画像，并补齐调用方需要的字段。输入为空、模型调用失败、返回内容无法解析或画像缺少核心内容时，
 * 本组件按岗位大类返回可编辑的兜底画像而不是向上抛出失败；上游当前会把该结果作为待确认画像继续保存。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JdAnalysisAgent {

    private static final String SYSTEM_PROMPT = """
            你是一个专业的岗位解析助手，负责从 JD（岗位描述）中提取关键信息并生成结构化的岗位画像。
            你需要结合 JD 原文和互联网行业通用面试考察点，推测技能要求、考察方向和面试重点。
            输出必须是合法 JSON，不要添加 Markdown 代码块或额外说明。
            """;

    /**
     * 按岗位大类硬编码的参考知识。
     *
     * <p>调用模型时会把这段内容与 JD 正文一起放入提示词，用于在 JD 信息不足时补充技能和考察方向。
     */
    private static final String POSITION_KNOWLEDGE_BASE = """
            【Java 高级工程师（2025-2026）】
            核心技能：Java 8/17/21 新特性（Stream、Optional、Record、模式匹配、虚拟线程 Virtual Threads、结构化并发 Structured Concurrency）；集合源码（HashMap、ConcurrentHashMap）；JVM（内存模型、G1/ZGC、类加载、双亲委派、OOM/Arthas 排查）；并发编程（synchronized 底层、volatile、AQS、JUC、线程池、CompletableFuture、Fork/Join）；Spring 全家桶（IoC/AOP 源码、Spring Boot 3.x 自动配置、事务传播与隔离、循环依赖、三级缓存、Spring Cloud/Dubbo 微服务治理）；MySQL（索引 B+树、MVCC、锁、慢查询优化、分库分表）；Redis（数据结构、持久化 RDB/AOF、集群、缓存穿透/击穿/雪崩、分布式锁）；消息队列（Kafka/RocketMQ 可靠性、顺序消息、死信队列）；分布式（CAP/BASE、分布式锁、分布式事务 Seata/TCC/Saga、分布式 ID）；设计模式；Netty；云原生（Docker/Kubernetes、GraalVM Native Image、CI/CD）；AI 工程化（RAG 检索增强生成、Agent 系统设计、Function Calling、向量数据库选型、LLM 服务稳定性与降级）。
            考察方向（按 2026 大厂面试权重：基础 25%、Spring 20%、中间件 25%、系统设计 20%、算法 10%）：JDK21 虚拟线程与结构化并发落地、JVM 调优与线上 OOM 排查、Spring 源码与事务失效场景、MySQL 索引与 MVCC、Redis 高可用与缓存设计、高并发秒杀/订单/限流熔断、微服务治理与分布式事务、系统设计与架构选型、线上问题排查方法论、AI 集成与 RAG/Agent 工程实践。

            【前端高级工程师（2025-2026）】
            核心技能：JavaScript/TypeScript 深入（原型链、闭包、事件循环、Promise/async-await、类型系统、泛型、类型守卫、装饰器）；React/Vue/Next.js/Nuxt.js（React Server Components、Vue3 Proxy 与编译优化、Composition API、Hooks、Fiber、Diff、状态管理 Zustand/Redux/Pinia/Jotai）；前端工程化（Vite/Webpack/Rspack/Rolldown、CI/CD、Monorepo、pnpm workspace、Turborepo、ESLint/Husky/lint-staged）；性能优化（Core Web Vitals：LCP/FCP/CLS/INP、代码分割、懒加载、SSR/SSG、资源预加载、重排重绘优化、Web Worker、长列表虚拟滚动）；浏览器与网络（渲染流水线、HTTP/2/3/QUIC、缓存、安全 CSP/XSS/CSRF）；测试（Jest/Vitest/Cypress/Playwright）；微前端（qiankun/single-spa/module-federation）；跨端（React Native/Flutter/UniApp/Tauri/鸿蒙原生）；AI 辅助研发与人机协作（Prompt Engineering、AI 代码审查与重构）。
            考察方向（按 2026 大厂面试权重：JS/TS 核心 30%、框架深度 25%、工程化 20%、浏览器原理 15%、业务与软技能 10%）：JS/TS 底层机制与手写能力、React/Vue 源码与 Hooks/Composition API、性能优化与 Core Web Vitals、工程化与构建工具链、浏览器渲染与网络安全、微前端与跨端架构、AI 辅助开发后的代码质量把控、项目难点与 STAR 法则表达。

            【产品经理（2025-2026）】
            核心技能：产品 sense 与需求分析、用户调研（访谈/问卷/可用性测试）、竞品分析、商业模式理解；需求优先级排序（KANO、RICE、价值/成本矩阵、MoSCoW）；数据分析（SQL、Excel、漏斗模型、AARRR、A/B 测试、归因分析）；产品设计（用户画像、用户旅程、流程图、PRD、原型 Axure/Figma/墨刀/ProcessOn）；项目管理（敏捷/Scrum、OKR、排期、风险管理）；跨部门沟通与影响力；STAR 行为面试表达。
            考察方向：产品设计与改进（CIRCLES）、需求优先级判断、从 0 到 1 产品落地、数据驱动决策、用户增长与留存、商业变现与竞品策略、跨团队冲突处理、项目复盘与风险管理、B2B/B2C/SaaS 产品差异。

            【算法工程师 / 大模型工程师（2025-2026）】
            核心技能：数据结构与算法（数组、链表、树、图、DP、字符串、海量数据）；传统机器学习（LR、SVM、决策树、集成学习、XGBoost/LightGBM、特征工程）；深度学习（CNN/RNN/LSTM、Transformer、注意力机制、RoPE、Flash Attention、MoE）；大模型（预训练、SFT、RLHF/DPO、LoRA/QLoRA、RAG、Prompt Engineering、Agent、Function Calling、模型评估）；Python/C++、PyTorch/TensorFlow；数学基础（线性代数、概率论、优化）；至少一个应用领域（推荐/搜索/NLP/CV）。
            考察方向：手撕算法题、ML/DL 模型原理与推导、Transformer 架构细节（RoPE/RMSNorm/SwiGLU/KV Cache/GQA）、大模型训练与微调（SFT/RLHF/DPO/LoRA）、RAG 与 Agent 工程、项目中的模型选型与调优、推荐/搜索/NLP 系统设计、推理部署与性能优化。

            【运营 / 增长（2025-2026）】
            核心技能：用户增长（AARRR、裂变、渠道投放、SEO/SEM、私域运营）、内容运营、活动运营、社群运营；数据分析（DAU/留存/转化率/LTV/ROI、漏斗、AB 测试）；用户生命周期管理；文案与创意；跨部门协作；项目管理。
            考察方向：增长策略制定、活动策划与复盘、用户分层与生命周期运营、内容/社群冷启动、数据指标拆解、渠道投放 ROI 优化、竞品与市场分析、危机公关与舆情处理。
            """;

    private static final String USER_PROMPT_TEMPLATE = """
            请根据以下 JD 文本生成结构化的岗位画像 JSON。

            **参考知识（用于补充 JD 中未明确描述的通用技能与考察点，请优先结合岗位大类使用对应板块）：**
            %s

            **提取与推理要求：**
            1. 优先从 JD 原文中提取岗位名称、公司、地点、薪资、等级、技能要求等信息。
            2. 如果 JD 中技能描述不够完整，请结合上方参考知识补充该岗位大类常见的核心技能要求，并标注 importance 为"必须"或"加分"。
            3. 技能深度等级使用 L1-L5：L1 了解概念、L2 能使用、L3 熟悉原理、L4 能优化/源码级理解、L5 专家级/架构级。
            4. 根据岗位类型和 JD 内容，推测 4-8 个重点考察方向（probingDirections），每个方向给出 priority（1-5，数字越小优先级越高）、depthRange（如 L3-L5）和 2-3 个示例问题（问题要贴合当前技术趋势）。
            5. interviewFocus 列出 3-5 个面试关注维度，如"技术深度"、"项目经验"、"系统设计"、"数据敏感度"、"问题解决能力"、"沟通表达"、"商业思维"等。
            6. 对 JD 中未明确的信息可合理推测，但需保持 confidenceLevel 准确（0.0-1.0，信息越完整越高）。

            **输出格式（只输出 JSON，不要添加 ```json 代码块标记或任何额外说明文字）：**
            {
              "basicInfo": {
                "title": "岗位名称",
                "company": "公司名称（JD 未提供则留空）",
                "location": "工作地点（JD 未提供则留空）",
                "level": "岗位等级，如 初级/中级/高级/专家",
                "salaryRange": "薪资范围（JD 未提供则留空）"
              },
              "requiredSkills": [
                { "skill": "Java", "importance": "必须", "depth": "L3-L4" }
              ],
              "preferredSkills": [
                { "skill": "Redis", "importance": "加分", "depth": "L2-L3" }
              ],
              "probingDirections": [
                {
                  "direction": "并发编程",
                  "priority": 1,
                  "depthRange": "L3-L5",
                  "sampleQuestions": ["synchronized 底层原理与锁升级过程", "JDK21 虚拟线程的适用场景与注意事项"]
                }
              ],
              "interviewFocus": ["技术深度", "源码理解", "问题解决能力"],
              "confidenceLevel": 0.9
            }

            **JD 文本：**
            %s
            """;

    private final LlmService llmService;
    private final ObjectMapper objectMapper;

    /**
     * 在未指定岗位大类时分析 JD 文本。
     *
     * @param jdText JD 文本
     * @return 模型画像或默认类别的兜底画像，不返回 {@code null}
     */
    public PositionProfileData analyze(String jdText) {
        return AgentContext.runAs(AgentType.JD_ANALYSIS, () -> analyze(jdText, null));
    }

    /**
     * 分析 JD 文本，并在无法得到有效模型结果时按岗位大类降级。
     *
     * <p>调用顺序为：校验正文 -> 拼入内置岗位参考内容 -> 调用模型 -> 提取并反序列化 JSON ->
     * 检查画像是否包含技能或考察方向。输入为空、模型调用或响应解析异常、核心内容为空时都会转为兜底画像，
     * 调用方需要结合该降级行为判断后续状态。
     *
     * @param jdText      JD 文本
     * @param jobCategory 岗位大类（如 TECH、PRODUCT），可为空
     * @return 模型画像或按岗位大类生成的兜底画像，不返回 {@code null}
     */
    public PositionProfileData analyze(String jdText, String jobCategory) {
        return AgentContext.runAs(AgentType.JD_ANALYSIS, () -> {
            // 1. 空正文不调用模型，直接按岗位大类生成可编辑画像。
            if (jdText == null || jdText.isBlank()) {
                log.warn("[JdAnalysisAgent] JD 文本为空，返回兜底画像");
                return fallbackProfile(jobCategory);
            }

            // 2. 将 JD 与通用考察知识一起交给模型，补足原文未明确写出的面试方向。
            String userPrompt = String.format(USER_PROMPT_TEMPLATE, POSITION_KNOWLEDGE_BASE, jdText);

            // 3. 模型异常、格式异常和核心内容为空都在本组件内降级，不中断上游解析流程。
            try {
                String response = llmService.chat(SYSTEM_PROMPT, userPrompt);
                PositionProfileData data = parseProfile(response);
                if (isEmptyProfile(data)) {
                    log.warn("[JdAnalysisAgent] LLM 返回空画像，使用兜底画像");
                    return fallbackProfile(jobCategory);
                }
                return data;
            } catch (Exception e) {
                log.error("[JdAnalysisAgent] LLM 解析失败，返回兜底画像", e);
                return fallbackProfile(jobCategory);
            }
        });
    }

    /**
     * 判断模型画像是否缺少可用于组织面试的核心内容。
     *
     * <p>仅必备技能、加分技能和考察方向三组数据参与判断；三者同时为空才视为空画像。
     * 基本信息、面试重点和置信度即使有值，也不会改变这个判断结果。
     */
    private boolean isEmptyProfile(PositionProfileData data) {
        if (data == null) {
            return true;
        }
        boolean noSkills = (data.getRequiredSkills() == null || data.getRequiredSkills().isEmpty())
                && (data.getPreferredSkills() == null || data.getPreferredSkills().isEmpty());
        boolean noDirections = data.getProbingDirections() == null || data.getProbingDirections().isEmpty();
        return noSkills && noDirections;
    }

    /**
     * 从模型响应中提取并还原岗位画像，同时补齐上游依赖的默认字段。
     *
     * <p>缺失的基本信息、集合和置信度会分别补为空对象、空集合和 {@code 0.0}；响应无法解析时返回空画像，
     * 随后由上层判空并切换到按岗位大类生成的兜底画像。
     */
    private PositionProfileData parseProfile(String rawResponse) {
        String json = extractJson(rawResponse);
        try {
            PositionProfileData data = objectMapper.readValue(json, PositionProfileData.class);
            if (data == null) {
                return PositionProfileData.empty();
            }
            // 将缺失字段统一为空对象或集合，避免上游在保存和展示画像时额外处理 null。
            if (data.getBasicInfo() == null) data.setBasicInfo(new PositionProfileData.BasicInfo());
            if (data.getRequiredSkills() == null) data.setRequiredSkills(List.of());
            if (data.getPreferredSkills() == null) data.setPreferredSkills(List.of());
            if (data.getProbingDirections() == null) data.setProbingDirections(List.of());
            if (data.getInterviewFocus() == null) data.setInterviewFocus(List.of());
            if (data.getConfidenceLevel() == null) data.setConfidenceLevel(0.0);
            return data;
        } catch (JsonProcessingException e) {
            log.error("[JdAnalysisAgent] LLM 返回 JSON 解析失败: {}", json, e);
            return PositionProfileData.empty();
        }
    }

    /**
     * 从 LLM 响应中提取 JSON 内容，支持 Markdown 代码块和普通 JSON。
     */
    private String extractJson(String rawResponse) {
        if (rawResponse == null || rawResponse.isBlank()) {
            return "{}";
        }
        String trimmed = rawResponse.trim();

        int codeBlockStart = trimmed.indexOf("```");
        if (codeBlockStart != -1) {
            int contentStart = trimmed.indexOf('\n', codeBlockStart);
            if (contentStart == -1) {
                contentStart = codeBlockStart + 3;
            } else {
                contentStart = contentStart + 1;
            }
            int codeBlockEnd = trimmed.lastIndexOf("```");
            if (codeBlockEnd > codeBlockStart) {
                return trimmed.substring(contentStart, codeBlockEnd).trim();
            }
        }

        int firstBrace = trimmed.indexOf('{');
        int lastBrace = trimmed.lastIndexOf('}');
        if (firstBrace != -1 && lastBrace != -1 && lastBrace > firstBrace) {
            return trimmed.substring(firstBrace, lastBrace + 1);
        }

        return trimmed;
    }

    /**
     * 按岗位大类生成可编辑的兜底画像。
     *
     * <p>技术、产品、运营和设计类分别填充面试重点与考察方向，未知类别使用通用内容；
     * 基本信息和必备、加分技能仍保持为空，置信度保持 {@code 0.0}。上游会把该结果当作正常画像保存并交给用户确认。
     */
    private PositionProfileData fallbackProfile(String jobCategory) {
        PositionProfileData data = PositionProfileData.empty();
        String category = jobCategory == null ? "" : jobCategory.toUpperCase();
        switch (category) {
            case "TECH", "JAVA", "FRONTEND" -> {
                data.setInterviewFocus(List.of("技术深度", "源码理解", "项目经验", "系统设计", "问题解决能力"));
                data.setProbingDirections(List.of(
                        direction("核心语言与基础", 1, "L3-L5", List.of("语言新特性与底层原理", "集合/并发基础")),
                        direction("框架与中间件", 2, "L3-L5", List.of("Spring/框架源码", "缓存/消息队列/数据库原理")),
                        direction("系统设计与高并发", 3, "L3-L5", List.of("高并发场景设计", "微服务治理与分布式事务")),
                        direction("线上问题排查", 4, "L3-L5", List.of("JVM/性能调优", "线上故障定位方法论"))
                ));
            }
            case "PRODUCT" -> {
                data.setInterviewFocus(List.of("产品思维", "数据分析", "沟通协作", "商业敏感度", "项目落地能力"));
                data.setProbingDirections(List.of(
                        direction("产品设计与需求分析", 1, "L3-L5", List.of("需求优先级排序", "产品方案设计")),
                        direction("数据驱动决策", 2, "L3-L5", List.of("指标体系搭建", "A/B 测试与实验分析")),
                        direction("用户增长与商业化", 3, "L3-L5", List.of("增长策略", "商业模式与变现路径")),
                        direction("跨团队推进", 4, "L3-L5", List.of("项目风险管理", "冲突协调与向上管理"))
                ));
            }
            case "OPERATION" -> {
                data.setInterviewFocus(List.of("数据敏感度", "增长思维", "执行力", "创意策划", "用户洞察"));
                data.setProbingDirections(List.of(
                        direction("用户增长", 1, "L3-L5", List.of("AARRR 模型应用", "渠道投放与裂变")),
                        direction("内容与活动运营", 2, "L3-L5", List.of("活动策划与复盘", "内容生态建设")),
                        direction("数据与指标", 3, "L3-L5", List.of("核心指标拆解", "ROI 分析")),
                        direction("用户生命周期", 4, "L3-L5", List.of("分层运营", "流失预警与召回"))
                ));
            }
            case "DESIGN" -> {
                data.setInterviewFocus(List.of("设计思维", "用户体验", "视觉表现", "沟通协作", "产品理解"));
                data.setProbingDirections(List.of(
                        direction("交互与用户体验", 1, "L3-L5", List.of("信息架构与流程设计", "可用性测试")),
                        direction("视觉与品牌", 2, "L3-L5", List.of("设计系统搭建", "视觉风格把控")),
                        direction("工具与落地", 3, "L3-L5", List.of("Figma/Sketch 高级技巧", "设计交付与走查")),
                        direction("跨团队协作", 4, "L3-L5", List.of("与产品/研发协作", "设计评审与说服"))
                ));
            }
            default -> {
                data.setInterviewFocus(List.of("专业能力", "项目经验", "沟通表达", "问题解决能力"));
                data.setProbingDirections(List.of(
                        direction("岗位核心技能", 1, "L3-L5", List.of("专业基础知识", "核心工具使用")),
                        direction("项目与实践", 2, "L3-L5", List.of("项目难点与解决方案", "量化成果")),
                        direction("综合素质", 3, "L3-L5", List.of("沟通协作", "学习迭代能力"))
                ));
            }
        }
        return data;
    }

    private PositionProfileData.ProbingDirection direction(String name, int priority, String depthRange, List<String> questions) {
        PositionProfileData.ProbingDirection d = new PositionProfileData.ProbingDirection();
        d.setDirection(name);
        d.setPriority(priority);
        d.setDepthRange(depthRange);
        d.setSampleQuestions(questions);
        return d;
    }
}
