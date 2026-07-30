package com.interviewcoach.interview.domain.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.interviewcoach.interview.domain.entity.InterviewPhase;
import com.interviewcoach.interview.domain.model.InterviewContext;
import com.interviewcoach.interview.infrastructure.tool.QuestionBankTool;
import com.interviewcoach.interview.infrastructure.tool.SkillsTool;
import com.interviewcoach.resume.domain.model.ResumeProfileAnalysisData;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class InterviewerAgentTest {

    @Test
    void shouldExposeSkillAssessmentOnlyAsVerificationHint() {
        LlmInterviewService llmService = Mockito.mock(LlmInterviewService.class);
        SkillsTool skillsTool = Mockito.mock(SkillsTool.class);
        QuestionBankTool questionBankTool = Mockito.mock(QuestionBankTool.class);
        InterviewerAgent agent = new InterviewerAgent(
                llmService, new ObjectMapper(), skillsTool, questionBankTool);
        ResumeProfileAnalysisData.SkillAssessment skill =
                new ResumeProfileAnalysisData.SkillAssessment();
        skill.setSkill("Java");
        skill.setInferredLevel("熟练");
        ResumeProfileAnalysisData analysis = new ResumeProfileAnalysisData();
        analysis.setStrengths(List.of());
        analysis.setVerificationPoints(List.of());
        analysis.setSkillAssessments(List.of(skill));
        InterviewContext context = new InterviewContext();
        context.setCurrentPhase(InterviewPhase.SELF_INTRO);
        context.setUserProfileAnalysis(analysis);
        when(skillsTool.getSkillPrompt(InterviewPhase.SELF_INTRO)).thenReturn("自我介绍规则");
        when(llmService.chat(anyString(), anyString())).thenReturn("{\"question\":\"请介绍一下自己\"}");
        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);

        String question = agent.generateSelfIntroQuestion(context);

        Mockito.verify(llmService).chat(anyString(), promptCaptor.capture());
        assertThat(question).isEqualTo("请介绍一下自己");
        assertThat(promptCaptor.getValue())
                .contains("推断技能水平（需验证）：Java：熟练", "只用于选题，不得直接作为评分或结论");
    }

    @Test
    void shouldIgnoreNullOptionalAnalysisItems() {
        LlmInterviewService llmService = Mockito.mock(LlmInterviewService.class);
        SkillsTool skillsTool = Mockito.mock(SkillsTool.class);
        QuestionBankTool questionBankTool = Mockito.mock(QuestionBankTool.class);
        InterviewerAgent agent = new InterviewerAgent(
                llmService, new ObjectMapper(), skillsTool, questionBankTool);
        ResumeProfileAnalysisData analysis = new ResumeProfileAnalysisData();
        analysis.setStrengths(Collections.singletonList(null));
        analysis.setVerificationPoints(Collections.singletonList(null));
        analysis.setSkillAssessments(Collections.singletonList(null));
        InterviewContext context = new InterviewContext();
        context.setCurrentPhase(InterviewPhase.SELF_INTRO);
        context.setUserProfileAnalysis(analysis);
        when(skillsTool.getSkillPrompt(InterviewPhase.SELF_INTRO)).thenReturn("自我介绍规则");
        when(llmService.chat(anyString(), anyString())).thenReturn("{\"question\":\"请介绍一下自己\"}");

        String question = agent.generateSelfIntroQuestion(context);

        assertThat(question).isEqualTo("请介绍一下自己");
    }
}
