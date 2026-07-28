package com.interviewcoach.resume.domain.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.interviewcoach.resume.domain.model.UserProfileData;
import com.interviewcoach.resume.infrastructure.ai.LlmService;
import com.interviewcoach.resume.infrastructure.ai.MockLlmService;
import com.interviewcoach.resume.infrastructure.desensitize.ResumeDesensitizer;
import org.junit.jupiter.api.Test;

/**
 * 简历分析智能体单元测试。
 */
class ResumeAnalysisAgentTest {

    private final LlmService llmService = new MockLlmService();
    private final ResumeDesensitizer desensitizer = new ResumeDesensitizer();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ResumeAnalysisAgent agent = new ResumeAnalysisAgent(llmService, desensitizer, objectMapper);

    @Test
    void shouldReturnEmptyProfileWhenResumeTextIsBlank() {
        UserProfileData result = agent.analyze("   ");

        assertThat(result).isNotNull();
        assertThat(result.getSkillTags()).isEmpty();
        assertThat(result.getConfidenceLevel()).isZero();
    }

    @Test
    void shouldAnalyzeResumeAndReturnProfile() {
        String resumeText = """
                张三，5年Java开发经验，精通Spring Boot、MySQL、Redis。
                曾在某互联网公司负责电商订单系统开发。
                手机：13800138000，邮箱：zhangsan@example.com
                """;

        UserProfileData result = agent.analyze(resumeText);

        assertThat(result).isNotNull();
        assertThat(result.getBasicInfo()).isNotNull();
        assertThat(result.getConfidenceLevel()).isNotNull();
    }

    @Test
    void shouldInferSeniorExperienceLevel() {
        UserProfileData data = UserProfileData.empty();
        UserProfileData.WorkExperience exp1 = new UserProfileData.WorkExperience();
        exp1.setDuration("2015.03 - 2022.03");
        data.setWorkExperience(java.util.List.of(exp1));

        String level = agent.inferExperienceLevel(data);

        assertThat(level).isEqualTo("SENIOR");
    }

    @Test
    void shouldInferSeniorFromWorkingYears() {
        UserProfileData data = UserProfileData.empty();
        data.setBasicInfo(new UserProfileData.BasicInfo());
        data.getBasicInfo().setWorkingYears("10年");

        String level = agent.inferExperienceLevel(data);

        assertThat(level).isEqualTo("SENIOR");
    }

    @Test
    void shouldInferSeniorFromChineseDateRange() {
        UserProfileData data = UserProfileData.empty();
        UserProfileData.WorkExperience exp1 = new UserProfileData.WorkExperience();
        exp1.setDuration("2015年6月 - 2025年6月");
        data.setWorkExperience(java.util.List.of(exp1));

        String level = agent.inferExperienceLevel(data);

        assertThat(level).isEqualTo("SENIOR");
    }

    @Test
    void shouldTrustLlmExperienceLevel() {
        UserProfileData data = UserProfileData.empty();
        data.setExperienceLevel("JUNIOR");
        data.setBasicInfo(new UserProfileData.BasicInfo());
        data.getBasicInfo().setWorkingYears("10年");

        String level = agent.inferExperienceLevel(data);

        assertThat(level).isEqualTo("JUNIOR");
    }

    @Test
    void shouldInferJuniorExperienceLevelByDefault() {
        String level = agent.inferExperienceLevel(UserProfileData.empty());

        assertThat(level).isEqualTo("JUNIOR");
    }
}
