package com.interviewcoach.resume.application.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.interviewcoach.common.exception.BusinessException;
import com.interviewcoach.resume.domain.model.UserProfileData;
import java.util.List;
import org.junit.jupiter.api.Test;

class ResumeProfileValidatorTest {

    private final ResumeProfileValidator validator = new ResumeProfileValidator();

    @Test
    void shouldAcceptEducationOnlyResumeFacts() {
        UserProfileData data = UserProfileData.empty();
        data.getBasicInfo().setEducation("软件工程本科");

        assertThatCode(() -> validator.validate(data)).doesNotThrowAnyException();
    }

    @Test
    void shouldRejectCompletelyEmptyFacts() {
        assertThatThrownBy(() -> validator.validate(UserProfileData.empty()))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void shouldRejectEmptyNestedObjectsAsResumeFacts() {
        UserProfileData data = UserProfileData.empty();
        data.setProjectExperience(List.of(new UserProfileData.ProjectExperience()));
        data.setWorkExperience(List.of(new UserProfileData.WorkExperience()));

        assertThatThrownBy(() -> validator.validate(data))
                .isInstanceOf(BusinessException.class);
    }
}
