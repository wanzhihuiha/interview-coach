package com.interviewcoach.resume.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.interviewcoach.common.exception.BusinessException;
import com.interviewcoach.resume.application.dto.ResumeUploadResponse;
import com.interviewcoach.resume.application.event.ResumeAiTaskLease;
import com.interviewcoach.resume.application.event.ResumeParseRequestedEvent;
import com.interviewcoach.resume.domain.entity.Resume;
import com.interviewcoach.resume.domain.entity.ResumeParseStatus;
import com.interviewcoach.resume.domain.repository.ResumeRepository;
import com.interviewcoach.resume.infrastructure.redis.ResumeAiTaskAdmissionService;
import com.interviewcoach.resume.infrastructure.redis.ResumeAiTaskLeaseRunner;
import com.interviewcoach.resume.infrastructure.redis.ResumeAiTaskProperties;
import com.interviewcoach.resume.infrastructure.redis.ResumeDailyCreationQuotaService;
import com.interviewcoach.resume.infrastructure.redis.ResumeDailyCreationQuotaService.CreationReservation;
import com.interviewcoach.resume.infrastructure.redis.ResumeUserMutationLock;
import com.interviewcoach.resume.infrastructure.storage.FileStorageService;
import com.interviewcoach.user.application.service.ConsentService;
import java.time.LocalDate;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.multipart.MultipartFile;

@ExtendWith(MockitoExtension.class)
class ResumeUploadServiceTest {

    @Mock private ResumeRepository resumeRepository;
    @Mock private ResumePersistenceService persistenceService;
    @Mock private FileStorageService fileStorageService;
    @Mock private ConsentService consentService;
    @Mock private ResumeUserMutationLock mutationLock;
    @Mock private ResumeDailyCreationQuotaService creationQuotaService;
    @Mock private ResumeAiTaskAdmissionService admissionService;
    @Mock private ResumeAiTaskLeaseRunner leaseRunner;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private MultipartFile file;

    private final ResumeAiTaskProperties properties = new ResumeAiTaskProperties();
    private final CreationReservation creationReservation =
            new CreationReservation(LocalDate.of(2026, 7, 30), "create-token");
    private final ResumeAiTaskLease taskLease = new ResumeAiTaskLease("user-permit", "resume-permit");
    private ResumeUploadService service;

    @BeforeEach
    void setUp() {
        service = new ResumeUploadService(
                resumeRepository,
                persistenceService,
                fileStorageService,
                consentService,
                mutationLock,
                creationQuotaService,
                admissionService,
                leaseRunner,
                properties,
                eventPublisher);
        ReflectionTestUtils.setField(service, "maxFileSize", 10_485_760L);
        lenient().doAnswer(invocation -> ((Supplier<?>) invocation.getArgument(1)).get())
                .when(mutationLock).executeChecked(anyLong(), any());
        when(file.isEmpty()).thenReturn(false);
        when(file.getSize()).thenReturn(1024L);
        lenient().when(file.getOriginalFilename()).thenReturn("resume.pdf");
        when(creationQuotaService.reserve(1L)).thenReturn(creationReservation);
    }

    @Test
    void shouldCreateFifthResumeAndDispatchWithLease() {
        Resume resume = pendingResume();
        when(resumeRepository.countByUserId(1L)).thenReturn(4L);
        when(fileStorageService.store(1L, file)).thenReturn("stored-file");
        when(persistenceService.createPending(1L, "resume.pdf", "stored-file", "PDF", 1024L))
                .thenReturn(resume);
        when(admissionService.acquire(1L, 11L)).thenReturn(taskLease);
        when(creationQuotaService.commit(1L, creationReservation)).thenReturn(true);

        ResumeUploadResponse response = service.upload(1L, file, "PDF");

        assertThat(response.getResumeId()).isEqualTo(11L);
        ArgumentCaptor<ResumeParseRequestedEvent> event =
                ArgumentCaptor.forClass(ResumeParseRequestedEvent.class);
        verify(eventPublisher).publishEvent(event.capture());
        assertThat(event.getValue().taskLease()).isSameAs(taskLease);
    }

    @Test
    void shouldRejectSixthRetainedResumeBeforeSavingFile() {
        when(resumeRepository.countByUserId(1L)).thenReturn(5L);

        assertThatThrownBy(() -> service.upload(1L, file, "PDF"))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getCode())
                        .isEqualTo(ResumeErrorCode.RETAINED_RESUME_LIMIT));

        verify(fileStorageService, never()).store(anyLong(), any());
        verify(creationQuotaService).release(1L, creationReservation);
    }

    @Test
    void shouldDeleteStoredFileWhenDatabaseCreationFails() {
        when(resumeRepository.countByUserId(1L)).thenReturn(0L);
        when(fileStorageService.store(1L, file)).thenReturn("stored-file");
        when(persistenceService.createPending(1L, "resume.pdf", "stored-file", "PDF", 1024L))
                .thenThrow(new IllegalStateException("database unavailable"));

        assertThatThrownBy(() -> service.upload(1L, file, "PDF"))
                .isInstanceOf(IllegalStateException.class);

        verify(fileStorageService).delete("stored-file");
        verify(creationQuotaService).release(1L, creationReservation);
    }

    @Test
    void shouldDeleteDatabaseRecordAndFileWhenAiAdmissionFails() {
        Resume resume = pendingResume();
        when(resumeRepository.countByUserId(1L)).thenReturn(0L);
        when(fileStorageService.store(1L, file)).thenReturn("stored-file");
        when(persistenceService.createPending(1L, "resume.pdf", "stored-file", "PDF", 1024L))
                .thenReturn(resume);
        when(admissionService.acquire(1L, 11L))
                .thenThrow(new BusinessException(
                        ResumeErrorCode.USER_AI_CONCURRENCY_LIMIT, "limit"));

        assertThatThrownBy(() -> service.upload(1L, file, "PDF"))
                .isInstanceOf(BusinessException.class);

        verify(persistenceService).deleteCreatedForCompensation(1L, 11L);
        verify(fileStorageService).delete("stored-file");
        verify(creationQuotaService).release(1L, creationReservation);
    }

    @Test
    void shouldCompensateCreatedResumeWhenRequiredTaskRegistrationFails() {
        Resume resume = pendingResume();
        when(resumeRepository.countByUserId(1L)).thenReturn(0L);
        when(fileStorageService.store(1L, file)).thenReturn("stored-file");
        when(persistenceService.createPending(1L, "resume.pdf", "stored-file", "PDF", 1024L))
                .thenReturn(resume);
        when(admissionService.acquire(1L, 11L)).thenReturn(taskLease);
        when(creationQuotaService.commit(1L, creationReservation)).thenReturn(true);
        doThrow(new IllegalStateException("event infrastructure unavailable"))
                .when(eventPublisher).publishEvent(any(ResumeParseRequestedEvent.class));

        assertThatThrownBy(() -> service.upload(1L, file, "PDF"))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getCode())
                        .isEqualTo(ResumeErrorCode.RESUME_TASK_INFRASTRUCTURE_UNAVAILABLE));

        verify(leaseRunner).releaseWithoutRun(1L, 11L, taskLease);
        verify(creationQuotaService).release(1L, creationReservation);
        verify(persistenceService).deleteCreatedForCompensation(1L, 11L);
        verify(fileStorageService).delete("stored-file");
    }

    @Test
    void shouldCompensateCommittedCreationWhenMutationLockIsLost() {
        Resume resume = pendingResume();
        when(resumeRepository.countByUserId(1L)).thenReturn(0L);
        when(fileStorageService.store(1L, file)).thenReturn("stored-file");
        when(persistenceService.createPending(1L, "resume.pdf", "stored-file", "PDF", 1024L))
                .thenReturn(resume);
        when(admissionService.acquire(1L, 11L)).thenReturn(taskLease);
        when(creationQuotaService.commit(1L, creationReservation)).thenReturn(true);
        doAnswer(invocation -> {
                    ((Supplier<?>) invocation.getArgument(1)).get();
                    throw new BusinessException(
                            ResumeErrorCode.RESUME_TASK_INFRASTRUCTURE_UNAVAILABLE, "lock lost");
                })
                .when(mutationLock).executeChecked(anyLong(), any());

        assertThatThrownBy(() -> service.upload(1L, file, "PDF"))
                .isInstanceOf(BusinessException.class);

        verify(leaseRunner).releaseWithoutRun(1L, 11L, taskLease);
        verify(creationQuotaService).release(1L, creationReservation);
        verify(persistenceService).deleteCreatedForCompensation(1L, 11L);
        verify(fileStorageService).delete("stored-file");
        verify(eventPublisher, never()).publishEvent(any(ResumeParseRequestedEvent.class));
    }

    private Resume pendingResume() {
        Resume resume = new Resume();
        resume.setId(11L);
        resume.setUserId(1L);
        resume.setResumeName("resume.pdf");
        resume.setParseStatus(ResumeParseStatus.PENDING);
        resume.setParseGeneration(1L);
        return resume;
    }
}
