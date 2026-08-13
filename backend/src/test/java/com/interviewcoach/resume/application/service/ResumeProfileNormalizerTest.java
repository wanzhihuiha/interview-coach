package com.interviewcoach.resume.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.interviewcoach.resume.domain.model.UserProfileData;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 验证无外部依赖的简历事实归一化器对技能别名、空集合元素、工作年限和经验等级的处理。
 *
 * <p>测试直接修改内存画像并观察规范化结果，不覆盖 JSON、数据库或模型调用。</p>
 */
class ResumeProfileNormalizerTest {

    /** 无可变外部状态的被测归一化器，供本类用例共享。 */
    private final ResumeProfileNormalizer normalizer = new ResumeProfileNormalizer();

    @Test
    void shouldNormalizeSkillAliasesAndInferExperienceLevel() {
        UserProfileData data = UserProfileData.empty();
        data.setSkillTags(List.of("java se", "Java", "springboot"));
        UserProfileData.WorkExperience work = new UserProfileData.WorkExperience();
        work.setDuration("2019-2024");
        data.setWorkExperience(List.of(work));

        normalizer.normalize(data);

        assertThat(data.getSkillTags()).containsExactly("Java", "Spring Boot");
        assertThat(normalizer.inferExperienceLevel(data)).isEqualTo("MID");
    }

    @Test
    void shouldDiscardNullExperienceItemsFromModelOutput() {
        UserProfileData data = UserProfileData.empty();
        data.setProjectExperience(new ArrayList<>(java.util.Collections.singletonList(null)));
        data.setWorkExperience(new ArrayList<>(java.util.Collections.singletonList(null)));

        normalizer.normalize(data);

        assertThat(data.getProjectExperience()).isEmpty();
        assertThat(data.getWorkExperience()).isEmpty();
        assertThat(normalizer.inferExperienceLevel(data)).isEqualTo("JUNIOR");
    }
}
