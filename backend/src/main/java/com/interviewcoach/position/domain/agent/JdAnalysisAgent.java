package com.interviewcoach.position.domain.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.interviewcoach.common.security.agent.AgentContext;
import com.interviewcoach.common.security.agent.AgentType;
import com.interviewcoach.position.domain.exception.PositionAnalysisException;
import com.interviewcoach.position.domain.exception.PositionAnalysisFailureCode;
import com.interviewcoach.position.domain.model.PositionProfileData;
import com.interviewcoach.resume.infrastructure.ai.LlmService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * JD 分析智能体，负责调用 LLM 解析岗位描述并生成岗位画像。
 */
@Component
@RequiredArgsConstructor
public class JdAnalysisAgent {

    private static final String SYSTEM_PROMPT = """
            你是一个专业的岗位解析助手，负责从 JD（岗位描述）中提取关键信息并生成结构化的岗位画像。
            你需要结合 JD 原文和互联网行业通用面试考察点，推测技能要求、考察方向和面试重点。
            输出必须是合法 JSON，不要添加 Markdown 代码块或额外说明。
            """;

    /**
     * 参考知识：不同岗位大类的常见技能要求与面试考察点。
     * 资料综合自 2025-2026 年 CSDN、掘金、知乎、牛客网、人人都是产品经理等平台的最新面试趋势，
     * 用于辅助 LLM 在 JD 信息不完整时补充合理的考察方向。
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
     * 分析 JD 文本并生成岗位画像。
     *
     * @param jdText JD 文本
     * @return 岗位画像数据
     */
    public PositionProfileData analyze(String jdText) {
        return analyze(jdText, null);
    }

    /**
     * 分析 JD 文本并生成必须通过结构校验的岗位画像；岗位大类保留为调用契约上下文。
     *
     * @param jdText      JD 文本
     * @param jobCategory 岗位大类（当前 prompt 已包含 JD，本阶段不用于失败兜底）
     * @return 通过校验的岗位画像数据
     */
    public PositionProfileData analyze(String jdText, String jobCategory) {
        return AgentContext.runAs(AgentType.JD_ANALYSIS, () -> analyzeRequired(jdText));
    }

    private PositionProfileData analyzeRequired(String jdText) {
        if (jdText == null || jdText.isBlank()) {
            throw new PositionAnalysisException(
                    PositionAnalysisFailureCode.INPUT_INVALID,
                    "岗位 JD 为空，请重新提交");
        }
        String userPrompt = String.format(USER_PROMPT_TEMPLATE, POSITION_KNOWLEDGE_BASE, jdText);
        String response;
        try {
            response = llmService.chat(SYSTEM_PROMPT, userPrompt);
        } catch (RuntimeException e) {
            throw new PositionAnalysisException(
                    PositionAnalysisFailureCode.LLM_REQUEST_FAILED,
                    "岗位解析模型调用失败，请重新解析",
                    e);
        }
        if (response == null || response.isBlank()) {
            throw new PositionAnalysisException(
                    PositionAnalysisFailureCode.LLM_EMPTY_RESPONSE,
                    "岗位解析模型返回空结果，请重新解析");
        }
        PositionProfileData profile = parseProfile(response);
        validateProfile(profile);
        return profile;
    }

    private PositionProfileData parseProfile(String rawResponse) {
        String json = extractJson(rawResponse);
        try {
            return objectMapper.readValue(json, PositionProfileData.class);
        } catch (JsonProcessingException e) {
            throw new PositionAnalysisException(
                    PositionAnalysisFailureCode.LLM_INVALID_JSON,
                    "岗位解析结果不是有效 JSON，请重新解析",
                    e);
        }
    }

    /**
     * 模型输出必须满足当前岗位画像契约；缺字段或空对象不能静默转成成功候选。
     */
    private void validateProfile(PositionProfileData profile) {
        if (profile == null
                || profile.getBasicInfo() == null
                || profile.getRequiredSkills() == null
                || profile.getPreferredSkills() == null
                || profile.getProbingDirections() == null
                || profile.getInterviewFocus() == null
                || profile.getConfidenceLevel() == null) {
            invalidProfile();
        }
        if (profile.getRequiredSkills().isEmpty()
                && profile.getPreferredSkills().isEmpty()
                && profile.getProbingDirections().isEmpty()) {
            invalidProfile();
        }
        profile.getRequiredSkills().forEach(this::validateSkill);
        profile.getPreferredSkills().forEach(this::validateSkill);
        profile.getProbingDirections().forEach(this::validateDirection);
        if (profile.getInterviewFocus().isEmpty()
                || profile.getInterviewFocus().stream().anyMatch(this::isBlank)) {
            invalidProfile();
        }
        double confidence = profile.getConfidenceLevel();
        if (!Double.isFinite(confidence) || confidence < 0.0 || confidence > 1.0) {
            invalidProfile();
        }
    }

    private void validateSkill(PositionProfileData.SkillItem skill) {
        if (skill == null
                || isBlank(skill.getSkill())
                || isBlank(skill.getImportance())
                || isBlank(skill.getDepth())) {
            invalidProfile();
        }
    }

    private void validateDirection(PositionProfileData.ProbingDirection direction) {
        if (direction == null
                || isBlank(direction.getDirection())
                || direction.getPriority() == null
                || direction.getPriority() < 1
                || direction.getPriority() > 5
                || isBlank(direction.getDepthRange())
                || direction.getSampleQuestions() == null
                || direction.getSampleQuestions().isEmpty()
                || direction.getSampleQuestions().stream().anyMatch(this::isBlank)) {
            invalidProfile();
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private void invalidProfile() {
        throw new PositionAnalysisException(
                PositionAnalysisFailureCode.LLM_INVALID_PROFILE,
                "岗位解析结果结构不完整，请重新解析");
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
}
