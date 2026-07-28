package com.interviewcoach.interview.domain.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.interviewcoach.common.security.agent.AgentContext;
import com.interviewcoach.common.security.agent.AgentType;
import com.interviewcoach.interview.application.dto.QuestionBankItem;
import com.interviewcoach.interview.domain.entity.InterviewPhase;
import com.interviewcoach.interview.domain.model.EvaluationSignal;
import com.interviewcoach.interview.domain.model.InterviewContext;
import com.interviewcoach.interview.infrastructure.tool.QuestionBankTool;
import com.interviewcoach.interview.infrastructure.tool.SkillsTool;
import com.interviewcoach.resume.domain.model.UserProfileData;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 面试官 Agent：负责各环节问题生成。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InterviewerAgent {

    private final LlmInterviewService llmService;
    private final ObjectMapper objectMapper;
    private final SkillsTool skillsTool;
    private final QuestionBankTool questionBankTool;

    public String generateFirstQuestion(InterviewContext context) {
        return AgentContext.runAs(AgentType.INTERVIEWER, () -> {
            log.info("[InterviewerAgent] 加载 {} Skill 编排", context.getCurrentPhase());
            if (context.getCurrentPhase() == InterviewPhase.SELF_INTRO) {
                return generateSelfIntroQuestion(context);
            }
            if (context.getCurrentPhase() == InterviewPhase.PROFESSIONAL) {
                return generateProfessionalQuestion(context, null, null, 1);
            }
            if (context.getCurrentPhase() == InterviewPhase.RESUME_DISCUSSION) {
                return generateResumeQuestion(context);
            }
            if (context.getCurrentPhase() == InterviewPhase.BEHAVIORAL) {
                return generateBehavioralQuestion(context);
            }
            return generateEndingMessage(context);
        });
    }

    public String generateSelfIntroQuestion(InterviewContext context) {
        return AgentContext.runAs(AgentType.INTERVIEWER, () -> {
            String skill = skillsTool.getSkillPrompt(InterviewPhase.SELF_INTRO);
            String system = "你是一个专业的面试官，正在进行一场模拟面试。请严格遵循下方的【自我介绍 Skill 编排】生成问题。只输出 JSON。"
                    + "\n\n【自我介绍 Skill 编排】\n" + skill;
            String user = buildContextPrompt(context)
                    + "\n当前已提问数：" + (context.getSelfIntroQuestionCount() == null ? 0 : context.getSelfIntroQuestionCount())
                    + "\n请生成一道自我介绍引导问题，要求：\n"
                    + "1. 让候选人介绍背景、工作经历和擅长的技术领域\n"
                    + "2. 自然、友好，帮助候选人放松\n"
                    + "输出格式：{\"question\":\"...\"}";
            return extractQuestion(llmService.chat(system, user));
        });
    }

    public String generateProfessionalQuestion(InterviewContext context, String previousQuestion,
                                                String previousAnswer, int targetDepth) {
        return AgentContext.runAs(AgentType.INTERVIEWER, () -> {
            // 非追问场景优先从永久 RAG 采样题目，降低 LLM 调用并沉淀高质量题目
            if (previousQuestion == null) {
                String bankQuestion = sampleFromPermanentBank(context);
                if (bankQuestion != null) {
                    log.info("[InterviewerAgent] 命中永久 RAG 题目, interviewId={}, topic={}",
                            context.getInterviewId(), context.getCurrentTopicId());
                    return bankQuestion;
                }
            }

            String skill = skillsTool.getSkillPrompt(InterviewPhase.PROFESSIONAL);
            String system = "你是一个资深的技术面试官，擅长通过递进式提问探测候选人真实技术水平。请严格遵循下方的【专业面试 Skill 编排】生成问题。只输出 JSON。"
                    + "\n\n【专业面试 Skill 编排】\n" + skill;
            StringBuilder user = new StringBuilder(buildContextPrompt(context));
            user.append("\n当前环节：专业面试");
            user.append("\n当前主题：").append(context.getCurrentTopicName());
            user.append("\n目标深度：L").append(targetDepth);
            if (previousQuestion != null) {
                user.append("\n上一轮问题：").append(previousQuestion);
                user.append("\n候选人回答：").append(previousAnswer);
                user.append("\n请基于回答进行追问，探测更深层的理解和实践能力。");
            } else {
                user.append("\n这是当前主题的第一题，请从基础概念开始提问。");
            }
            user.append("\n深度说明：L1基础概念、L2选型决策、L3原理机制、L4实践踩坑、L5深度扩展。");
            user.append("\n输出格式：{\"question\":\"...\",\"depth\":")
                    .append(targetDepth).append(",\"topicId\":\"").append(context.getCurrentTopicId()).append("\"}");
            return extractQuestion(llmService.chat(system, user.toString()));
        });
    }

    private String sampleFromPermanentBank(InterviewContext context) {
        try {
            String jobCategory = context.getJobCategory() == null ? "GENERAL" : context.getJobCategory();
            String phase = InterviewPhase.PROFESSIONAL.name();
            String topicId = context.getCurrentTopicId();
            double ratio = questionBankTool.decideBankRatio(jobCategory, topicId);
            if (ratio <= 0.0) {
                return null;
            }
            List<QuestionBankItem> items;
            if (topicId != null && !topicId.isBlank()) {
                items = questionBankTool.sampleFromPermanent(jobCategory, phase, topicId, 1);
            } else {
                items = questionBankTool.sampleFromPermanent(jobCategory, phase, 1);
            }
            if (items != null && !items.isEmpty()) {
                return items.get(0).getContent();
            }
        } catch (Exception e) {
            log.warn("[InterviewerAgent] 采样永久 RAG 失败，降级使用 LLM: {}", e.getMessage());
        }
        return null;
    }

    public String generateTopicTransition(InterviewContext context, String nextTopicName, String nextTopicId) {
        return AgentContext.runAs(AgentType.INTERVIEWER, () -> {
            String skill = skillsTool.getSkillPrompt(InterviewPhase.PROFESSIONAL);
            String system = "你是一个专业的面试官，需要在面试过程中自然切换技术主题。请遵循下方的【专业面试 Skill 编排】中的切换要求。只输出 JSON。"
                    + "\n\n【专业面试 Skill 编排】\n" + skill;
            String user = buildContextPrompt(context)
                    + "\n即将切换到的主题：" + nextTopicName
                    + "\n要求：自然过渡，不要生硬切换；简单回顾上主题，引出新主题；从新主题的基础概念开始。"
                    + "\n输出格式：{\"question\":\"过渡语+新主题第一题\"}";
            return extractQuestion(llmService.chat(system, user));
        });
    }

    public String generateResumeQuestion(InterviewContext context) {
        return AgentContext.runAs(AgentType.INTERVIEWER, () -> {
            UserProfileData profile = context.getUserProfile();
            List<UserProfileData.ProjectExperience> projects = profile != null ? profile.getProjectExperience() : null;
            int index = context.getCurrentProjectIndex() != null ? context.getCurrentProjectIndex() : 0;
            String projectName = (projects != null && index < projects.size()) ? projects.get(index).getName() : "你的项目";

            String skill = skillsTool.getSkillPrompt(InterviewPhase.RESUME_DISCUSSION);
            String system = "你是一个专业的面试官，正在进行简历探讨环节。请严格遵循下方的【简历探讨 Skill 编排】生成问题。只输出 JSON。"
                    + "\n\n【简历探讨 Skill 编排】\n" + skill;
            String user = buildContextPrompt(context)
                    + "\n当前探讨项目：" + projectName
                    + "\n项目索引：" + index
                    + "\n要求：生成一个深入探讨项目细节的问题，涉及技术挑战、角色贡献、成果量化或技术选型。"
                    + "\n输出格式：{\"question\":\"...\"}";
            return extractQuestion(llmService.chat(system, user));
        });
    }

    public String generateBehavioralQuestion(InterviewContext context) {
        return AgentContext.runAs(AgentType.INTERVIEWER, () -> {
            String[] scenarios = {"团队协作", "问题解决", "成长学习", "领导力", "沟通表达"};
            int index = context.getCurrentBehavioralIndex() != null ? context.getCurrentBehavioralIndex() : 0;
            String scenario = scenarios[index % scenarios.length];

            String skill = skillsTool.getSkillPrompt(InterviewPhase.BEHAVIORAL);
            String system = "你是一个专业的面试官，正在进行行为面试环节。请严格遵循下方的【行为面试 Skill 编排】生成问题。只输出 JSON。"
                    + "\n\n【行为面试 Skill 编排】\n" + skill;
            String user = buildContextPrompt(context)
                    + "\n本次场景类型：" + scenario
                    + "\n要求：生成一个 STAR 风格的问题，考察候选人的软技能和职业素养。"
                    + "\n输出格式：{\"question\":\"...\"}";
            return extractQuestion(llmService.chat(system, user));
        });
    }

    public String generateEndingMessage(InterviewContext context) {
        return AgentContext.runAs(AgentType.INTERVIEWER, () -> {
            String skill = skillsTool.getSkillPrompt(InterviewPhase.ENDING);
            String system = "你是一个专业的面试官，面试即将结束。请严格遵循下方的【结束环节 Skill 编排】生成结束语。只输出 JSON。"
                    + "\n\n【结束环节 Skill 编排】\n" + skill;
            String user = buildContextPrompt(context)
                    + "\n要求：生成一段结束语，总结面试并询问候选人是否有问题。"
                    + "\n输出格式：{\"question\":\"...\"}";
            return extractQuestion(llmService.chat(system, user));
        });
    }

    private String buildContextPrompt(InterviewContext context) {
        String positionName = "";
        String positionLevel = "";
        if (context.getPositionProfile() != null && context.getPositionProfile().getBasicInfo() != null) {
            positionName = context.getPositionProfile().getBasicInfo().getTitle();
            positionLevel = context.getPositionProfile().getBasicInfo().getLevel();
        }
        String userName = "";
        if (context.getUserProfile() != null && context.getUserProfile().getBasicInfo() != null) {
            userName = context.getUserProfile().getBasicInfo().getName();
        }
        return "**面试上下文**\n"
                + "- 岗位：" + positionName + "\n"
                + "- 岗位等级：" + positionLevel + "\n"
                + "- 候选人：" + userName;
    }

    private String extractQuestion(String json) {
        if (json == null || json.isBlank()) {
            return "请简要介绍一下自己。";
        }
        // 尝试直接解析 JSON
        try {
            String cleaned = json.trim();
            if (cleaned.startsWith("```")) {
                cleaned = cleaned.replaceAll("^```(json)?\\s*", "").replaceAll("\\s*```$", "").trim();
            }
            Map<?, ?> map = objectMapper.readValue(cleaned, Map.class);
            Object question = map.get("question");
            if (question != null) {
                return question.toString();
            }
        } catch (Exception e) {
            log.debug("[InterviewerAgent] JSON 解析失败，尝试正则提取: {}", e.getMessage());
        }
        // 兜底：尝试提取 "question" 字段值
        Matcher matcher = Pattern.compile("\"question\"\\s*:\\s*\"([^\"]+)\"").matcher(json);
        if (matcher.find()) {
            return matcher.group(1).replace("\\n", "\n");
        }
        // 最后的兜底：直接返回文本
        return json.replaceAll("^```(json)?\\s*", "").replaceAll("\\s*```$", "").trim();
    }
}
