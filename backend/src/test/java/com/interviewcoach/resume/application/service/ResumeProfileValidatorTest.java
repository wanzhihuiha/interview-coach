package com.interviewcoach.resume.application.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.interviewcoach.common.exception.BusinessException;
import com.interviewcoach.resume.domain.model.UserProfileData;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 验证正式简历事实画像在确认前必须满足的最小有效内容边界。
 *
 * <p>测试直接校验内存画像，区分可接受的教育事实、完全空画像和仅含空嵌套对象的无效画像。</p>
 */
class ResumeProfileValidatorTest {

    /** 无外部依赖的被测画像校验器，供本类用例共享。 */
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
