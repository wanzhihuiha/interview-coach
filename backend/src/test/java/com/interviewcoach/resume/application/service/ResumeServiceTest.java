package com.interviewcoach.resume.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.interviewcoach.common.exception.BusinessException;
import com.interviewcoach.resume.application.dto.ConfirmResumeRequest;
import com.interviewcoach.resume.application.dto.ResumeDetailResponse;
import com.interviewcoach.resume.application.dto.ResumeListResponse;
import com.interviewcoach.resume.application.dto.ResumeParseStatusResponse;
import com.interviewcoach.resume.application.dto.ResumeProfileResponse;
import com.interviewcoach.resume.domain.entity.Resume;
import com.interviewcoach.resume.domain.entity.ResumeParseStatus;
import com.interviewcoach.resume.domain.entity.ResumeProfile;
import com.interviewcoach.resume.domain.model.UserProfileData;
import com.interviewcoach.resume.domain.repository.ResumeProfileRepository;
import com.interviewcoach.resume.domain.repository.ResumeRepository;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * 简历应用服务单元测试。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ResumeServiceTest {

    @Autowired
    private ResumeService resumeService;

    @Autowired
    private ResumeRepository resumeRepository;

    @Autowired
    private ResumeProfileRepository resumeProfileRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private StringRedisTemplate redisTemplate;

    @Test
    void shouldListResumesForUser() {
        Long userId = 1L;
        createResume(userId, "简历1.pdf", ResumeParseStatus.CONFIRMED);
        createResume(userId, "简历2.txt", ResumeParseStatus.PENDING);

        ResumeListResponse response = resumeService.listResumes(userId, 0, 10);

        assertThat(response.getContent()).hasSize(2);
        assertThat(response.getTotalElements()).isEqualTo(2);
        assertThat(response.getContent()).allSatisfy(item ->
                assertThat(item.getJobCategoryLabel()).isEqualTo("技术类"));
    }

    @Test
    void shouldGetResumeDetailWithParsedData() throws Exception {
        Long userId = 1L;
        Resume resume = createResume(userId, "简历1.pdf", ResumeParseStatus.PENDING_CONFIRM);
        UserProfileData profileData = UserProfileData.empty();
        profileData.setSkillTags(List.of("Java", "Spring Boot"));
        ResumeProfile profile = new ResumeProfile();
        profile.setResumeId(resume.getId());
        profile.setUserId(userId);
        profile.setProfileData(objectMapper.writeValueAsString(profileData));
        profile.setExperienceLevel("MID");
        resumeProfileRepository.save(profile);

        ResumeDetailResponse detail = resumeService.getResumeDetail(userId, resume.getId());

        assertThat(detail.getFileName()).isEqualTo("简历1.pdf");
        assertThat(detail.getStatusLabel()).isEqualTo("待确认");
        assertThat(detail.getJobCategoryLabel()).isEqualTo("技术类");
        assertThat(detail.getParsedData()).isNotNull();
        assertThat(detail.getParsedData().getSkillTags()).containsExactly("Java", "Spring Boot");
    }

    @Test
    void shouldConfirmResumeAndUpdateStatus() throws Exception {
        Long userId = 1L;
        Resume resume = createResume(userId, "简历1.pdf", ResumeParseStatus.PENDING_CONFIRM);
        ResumeProfile profile = new ResumeProfile();
        profile.setResumeId(resume.getId());
        profile.setUserId(userId);
        profile.setProfileData(objectMapper.writeValueAsString(UserProfileData.empty()));
        resumeProfileRepository.save(profile);

        ConfirmResumeRequest request = new ConfirmResumeRequest();
        UserProfileData confirmedData = UserProfileData.empty();
        confirmedData.setSkillTags(List.of("Java"));
        request.setProfile(confirmedData);

        resumeService.confirmResume(userId, resume.getId(), request.getProfile());

        Resume updated = resumeRepository.findById(resume.getId()).orElseThrow();
        assertThat(updated.getParseStatus()).isEqualTo(ResumeParseStatus.CONFIRMED);
    }

    @Test
    void shouldThrowExceptionWhenConfirmingNonPendingConfirmResume() {
        Long userId = 1L;
        Resume resume = createResume(userId, "简历1.pdf", ResumeParseStatus.PENDING);

        assertThatThrownBy(() -> resumeService.confirmResume(userId, resume.getId(), UserProfileData.empty()))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(ResumeErrorCode.RESUME_STATUS_INVALID));
    }

    @Test
    void shouldGetResumeProfile() throws Exception {
        Long userId = 1L;
        Resume resume = createResume(userId, "简历1.pdf", ResumeParseStatus.CONFIRMED);
        UserProfileData profileData = UserProfileData.empty();
        profileData.setSkillTags(List.of("MySQL"));
        ResumeProfile profile = new ResumeProfile();
        profile.setResumeId(resume.getId());
        profile.setUserId(userId);
        profile.setProfileData(objectMapper.writeValueAsString(profileData));
        profile.setExperienceLevel("JUNIOR");
        resumeProfileRepository.save(profile);

        ResumeProfileResponse response = resumeService.getResumeProfile(userId, resume.getId());

        assertThat(response.getProfile()).isNotNull();
        assertThat(response.getProfile().getSkillTags()).containsExactly("MySQL");
        assertThat(response.getExperienceLevel()).isEqualTo("JUNIOR");
        assertThat(response.getExperienceLevelLabel()).isEqualTo("初级");
        assertThat(response.getStatusLabel()).isEqualTo("已确认");
    }

    @Test
    void shouldQueueReparseAndExposePendingStatus() {
        Long userId = 1L;
        Resume resume = createResume(userId, "简历1.pdf", ResumeParseStatus.PENDING_CONFIRM);

        resumeService.reparseResume(userId, resume.getId());

        ResumeParseStatusResponse status = resumeService.getParseStatus(userId, resume.getId());
        assertThat(status.getStatus()).isEqualTo("PENDING");
        assertThat(status.getStatusLabel()).isEqualTo("待解析");
        assertThat(status.getParseProgress()).isEqualTo(10);
    }

    @Test
    void shouldDeleteResumeSuccessfully() {
        Long userId = 1L;
        Resume resume = createResume(userId, "简历1.pdf", ResumeParseStatus.PENDING);

        resumeService.deleteResume(userId, resume.getId());

        assertThat(resumeRepository.findById(resume.getId())).isEmpty();
    }

    @Test
    void shouldThrowExceptionWhenDeletingLockedResume() {
        Long userId = 1L;
        Resume resume = createResume(userId, "简历1.pdf", ResumeParseStatus.CONFIRMED);
        resume.setLockInterviewId(100L);
        resumeRepository.save(resume);

        assertThatThrownBy(() -> resumeService.deleteResume(userId, resume.getId()))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(ResumeErrorCode.RESUME_LOCKED_FOR_DELETE));
    }

    @Test
    void shouldThrowExceptionWhenAccessingOtherUserResume() {
        Long ownerId = 1L;
        Long otherUserId = 2L;
        Resume resume = createResume(ownerId, "简历1.pdf", ResumeParseStatus.PENDING);

        assertThatThrownBy(() -> resumeService.getResumeDetail(otherUserId, resume.getId()))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(ResumeErrorCode.RESUME_NOT_FOUND));
    }

    private Resume createResume(Long userId, String name, ResumeParseStatus status) {
        Resume resume = new Resume();
        resume.setUserId(userId);
        resume.setResumeName(name);
        resume.setFileType(name.endsWith(".pdf") ? "PDF" : "TXT");
        resume.setFileSize(1024L);
        resume.setFilePath("/tmp/" + name);
        resume.setParseStatus(status);
        resume.setJobCategory("TECH");
        return resumeRepository.save(resume);
    }
}
