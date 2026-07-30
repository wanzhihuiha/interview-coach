package com.interviewcoach.resume.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.interviewcoach.resume.domain.model.UserProfileData;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ResumeProfileNormalizerTest {

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
