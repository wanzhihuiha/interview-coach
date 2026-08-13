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

/**
 * 验证简历上传服务对协议、用户锁、每日创建额度、文件落盘、数据库登记、AI 许可、事件交接和逆向补偿的编排。
 *
 * <p>所有外部协作者均为 Mock；固定创建预留和任务租约只代表默认成功场景，用例不执行真实文件、Redis、数据库或事件监听器。</p>
 */
@ExtendWith(MockitoExtension.class)
class ResumeUploadServiceTest {

    /** 模拟用户已保留简历数量查询。 */
    @Mock private ResumeRepository resumeRepository;
    /** 模拟 PENDING 简历登记及失败补偿删除。 */
    @Mock private ResumePersistenceService persistenceService;
    /** 模拟上传文件落盘和补偿删除。 */
    @Mock private FileStorageService fileStorageService;
    /** 模拟上传前所需隐私协议校验。 */
    @Mock private ConsentService consentService;
    /** 模拟串行化同一用户简历变更的锁。 */
    @Mock private ResumeUserMutationLock mutationLock;
    /** 模拟每日创建额度的预留、提交和释放。 */
    @Mock private ResumeDailyCreationQuotaService creationQuotaService;
    /** 模拟数据库登记后取得用户与简历两级 AI 许可。 */
    @Mock private ResumeAiTaskAdmissionService admissionService;
    /** 模拟事件未成功进入 Worker 时释放许可。 */
    @Mock private ResumeAiTaskLeaseRunner leaseRunner;
    /** 模拟把必需解析任务交给事务后监听器。 */
    @Mock private ApplicationEventPublisher eventPublisher;
    /** 提供文件名、大小和内容状态的上传文件 Mock。 */
    @Mock private MultipartFile file;

    /** 使用默认值参与保留数量和 AI 准入策略的测试配置。 */
    private final ResumeAiTaskProperties properties = new ResumeAiTaskProperties();
    /** 固定日期与 token 的每日创建预留 fixture，不代表不可配置业务规则。 */
    private final CreationReservation creationReservation =
            new CreationReservation(LocalDate.of(2026, 7, 30), "create-token");
    /** 固定许可 ID 的任务租约 fixture，用于验证事件交接和补偿对象一致。 */
    private final ResumeAiTaskLease taskLease = new ResumeAiTaskLease("user-permit", "resume-permit");
    /** 使用上述配置、fixture 和 Mock 构造的被测上传服务。 */
    private ResumeUploadService service;

    /** 每例重建服务并建立可复用的 10 MiB 上限、锁内执行、非空 PDF 和创建预留默认场景。 */
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
