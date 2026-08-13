package com.interviewcoach.resume.domain.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.interviewcoach.resume.domain.model.UserProfileData;
import com.interviewcoach.resume.infrastructure.ai.LlmService;
import com.interviewcoach.resume.infrastructure.desensitize.ResumeDesensitizer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * 验证简历事实提取 Agent 的输入脱敏、模型调用前回调、事实 JSON 解析和非法输入失败边界。
 *
 * <p>模型服务均由内存桩替代；用例只观察发送文本和解析结果，不连接外部模型，也不把保留的学校或公司名称视为已完整脱敏证明。</p>
 */
class ResumeAnalysisAgentTest {

    @Test
    void shouldSendDesensitizedTextAndReturnFacts() {
        AtomicReference<String> prompt = new AtomicReference<>();
        LlmService llmService = (system, user) -> {
            prompt.set(user);
            return """
                    {
                      "basicInfo":{"workingYears":"5年","currentPosition":"Java工程师","education":"软件工程本科"},
                      "skillTags":["Java"],
                      "skillLevel":{"Java":"熟练"},
                      "projectExperience":[],
                      "workExperience":[]
                    }
                    """;
        };
        ResumeAnalysisAgent agent = new ResumeAnalysisAgent(
                llmService, new ResumeDesensitizer(), new ObjectMapper());

        UserProfileData result = agent.analyze("""
                张三
                手机：13800138000，邮箱：zhangsan@example.com
                生日：1995-01-02
                地址：上海市某区某路1号
                曾在示例科技有限公司负责Java项目。
                """);

        assertThat(result.getSkillTags()).containsExactly("Java");
        assertThat(prompt.get()).doesNotContain("张三", "13800138000", "zhangsan@example.com", "1995-01-02", "某路1号");
        assertThat(prompt.get()).contains("示例科技有限公司");
    }

    @Test
    void shouldFailWhenResumeTextIsBlank() {
        ResumeAnalysisAgent agent = new ResumeAnalysisAgent(
                (system, user) -> "{}", new ResumeDesensitizer(), new ObjectMapper());

        assertThatThrownBy(() -> agent.analyze("   "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldFailWhenModelReturnsInvalidJson() {
        ResumeAnalysisAgent agent = new ResumeAnalysisAgent(
                (system, user) -> "not-json", new ResumeDesensitizer(), new ObjectMapper());

        assertThatThrownBy(() -> agent.analyze("Java开发经历"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRunAttemptCallbackAfterDesensitizationAndPromptPreparation() {
        List<String> steps = new ArrayList<>();
        ResumeDesensitizer desensitizer = new ResumeDesensitizer() {
            @Override
            public DesensitizationResult desensitize(String text) {
                steps.add("desensitize");
                return super.desensitize(text);
            }
        };
        ResumeAnalysisAgent agent = new ResumeAnalysisAgent((system, user) -> {
            steps.add("chat");
            return "{\"basicInfo\":{},\"skillTags\":[],\"skillLevel\":{},"
                    + "\"projectExperience\":[],\"workExperience\":[]}";
        }, desensitizer, new ObjectMapper());

        agent.analyze("Java 开发经历", () -> steps.add("callback"));

        assertThat(steps).containsExactly("desensitize", "callback", "chat");
    }
}
