package com.interviewcoach.resume.domain.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.interviewcoach.resume.domain.entity.ResumeProfileAnalysisMode;
import com.interviewcoach.resume.domain.model.ResumeProfileAnalysisData;
import com.interviewcoach.resume.domain.model.UserProfileData;
import com.interviewcoach.resume.infrastructure.desensitize.ResumeDesensitizer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ResumeProfileAnalysisAgentTest {

    @Test
    void shouldReturnEvidenceBoundAnalysis() {
        ResumeProfileAnalysisAgent agent = new ResumeProfileAnalysisAgent((system, user) -> """
                {
                  "strengths":[{"content":"项目经验完整","evidenceRefs":["project:0"],"confidence":0.8}],
                  "verificationPoints":[{"content":"验证并发处理深度","evidenceRefs":["project:0"],"confidence":0.6}],
                  "skillAssessments":[{"skill":"Java","inferredLevel":"熟练","evidenceRefs":["work:0"],"confidence":0.7}]
                }
                """, new ObjectMapper(), new ResumeDesensitizer());
        UserProfileData profile = UserProfileData.empty();
        profile.setSkillTags(List.of("Java"));

        ResumeProfileAnalysisData result = agent.analyze(profile);

        assertThat(result.getStrengths()).singleElement()
                .satisfies(item -> assertThat(item.getEvidenceRefs()).containsExactly("project:0"));
        assertThat(result.getVerificationPoints()).hasSize(1);
        assertThat(result.getSkillAssessments()).hasSize(1);
    }

    @Test
    void shouldDesensitizeConfirmedFactsBeforeAnalysis() {
        AtomicReference<String> sentPrompt = new AtomicReference<>();
        ResumeProfileAnalysisAgent agent = new ResumeProfileAnalysisAgent((system, user) -> {
            sentPrompt.set(user);
            return "{\"strengths\":[],\"verificationPoints\":[],\"skillAssessments\":[]}";
        }, new ObjectMapper(), new ResumeDesensitizer());
        UserProfileData profile = UserProfileData.empty();
        profile.setSkillTags(List.of("Java"));
        UserProfileData.WorkExperience work = new UserProfileData.WorkExperience();
        work.setCompany("示例科技有限公司");
        work.setHighlights(List.of("联系方式 13800138000，邮箱 zhangsan@example.com"));
        profile.setWorkExperience(List.of(work));

        agent.analyze(profile);

        assertThat(sentPrompt.get()).doesNotContain("13800138000", "zhangsan@example.com");
        assertThat(sentPrompt.get()).contains("示例科技有限公司", "[PHONE]", "[EMAIL]");
    }

    @Test
    void shouldUseDesensitizedRetainedAnalysisAndFeedbackForRefine() {
        AtomicReference<String> sentPrompt = new AtomicReference<>();
        ResumeProfileAnalysisAgent agent = new ResumeProfileAnalysisAgent((system, user) -> {
            sentPrompt.set(user);
            return "{\"strengths\":[],\"verificationPoints\":[],\"skillAssessments\":[]}";
        }, new ObjectMapper(), new ResumeDesensitizer());
        UserProfileData profile = UserProfileData.empty();
        profile.setSkillTags(List.of("Java"));
        ResumeProfileAnalysisData previous = new ResumeProfileAnalysisData();
        ResumeProfileAnalysisData.AnalysisItem item = new ResumeProfileAnalysisData.AnalysisItem();
        item.setContent("旧分析联系方式 13800138000");
        previous.setStrengths(List.of(item));

        agent.analyze(
                profile,
                previous,
                "请联系 zhangsan@example.com，并关注工程证据",
                ResumeProfileAnalysisMode.REFINE,
                () -> { });

        assertThat(sentPrompt.get())
                .contains("上一次成功分析", "本次用户意见", "[PHONE]", "[EMAIL]", "关注工程证据")
                .doesNotContain("13800138000", "zhangsan@example.com");
    }

    @Test
    void shouldIgnoreRetainedAnalysisAndFeedbackForRegenerate() {
        AtomicReference<String> sentPrompt = new AtomicReference<>();
        ResumeProfileAnalysisAgent agent = new ResumeProfileAnalysisAgent((system, user) -> {
            sentPrompt.set(user);
            return "{\"strengths\":[],\"verificationPoints\":[],\"skillAssessments\":[]}";
        }, new ObjectMapper(), new ResumeDesensitizer());
        ResumeProfileAnalysisData previous = new ResumeProfileAnalysisData();
        ResumeProfileAnalysisData.AnalysisItem item = new ResumeProfileAnalysisData.AnalysisItem();
        item.setContent("OLD_ANALYSIS_MARKER");
        previous.setStrengths(List.of(item));

        agent.analyze(
                UserProfileData.empty(),
                previous,
                "FEEDBACK_MARKER",
                ResumeProfileAnalysisMode.REGENERATE,
                () -> { });

        assertThat(sentPrompt.get())
                .doesNotContain("OLD_ANALYSIS_MARKER", "FEEDBACK_MARKER", "上一次成功分析");
    }

    @Test
    void shouldRunCallbackAfterAllInputPreparationAndImmediatelyBeforeChat() {
        List<String> steps = new ArrayList<>();
        ResumeDesensitizer desensitizer = new ResumeDesensitizer() {
            @Override
            public DesensitizationResult desensitize(String text) {
                steps.add("desensitize");
                return super.desensitize(text);
            }
        };
        ResumeProfileAnalysisAgent agent = new ResumeProfileAnalysisAgent((system, user) -> {
            steps.add("chat");
            return "{\"strengths\":[],\"verificationPoints\":[],\"skillAssessments\":[]}";
        }, new ObjectMapper(), desensitizer);
        ResumeProfileAnalysisData previous = new ResumeProfileAnalysisData();
        previous.setStrengths(List.of());

        agent.analyze(
                UserProfileData.empty(),
                previous,
                "关注项目证据",
                ResumeProfileAnalysisMode.REFINE,
                () -> steps.add("callback"));

        assertThat(steps).containsExactly(
                "desensitize", "desensitize", "desensitize", "callback", "chat");
    }

    @Test
    void shouldNotCountBeforeRefineInputValidationCompletes() {
        AtomicInteger callbacks = new AtomicInteger();
        AtomicInteger modelCalls = new AtomicInteger();
        ResumeProfileAnalysisAgent agent = new ResumeProfileAnalysisAgent((system, user) -> {
            modelCalls.incrementAndGet();
            return "{}";
        }, new ObjectMapper(), new ResumeDesensitizer());

        assertThatThrownBy(() -> agent.analyze(
                UserProfileData.empty(),
                null,
                "反馈",
                ResumeProfileAnalysisMode.REFINE,
                callbacks::incrementAndGet))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(callbacks).hasValue(0);
        assertThat(modelCalls).hasValue(0);
    }
}
