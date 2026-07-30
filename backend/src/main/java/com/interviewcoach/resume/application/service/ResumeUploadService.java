package com.interviewcoach.resume.application.service;

import com.interviewcoach.common.exception.BusinessException;
import com.interviewcoach.resume.application.dto.ResumeUploadResponse;
import com.interviewcoach.resume.application.event.ResumeAiTaskLease;
import com.interviewcoach.resume.application.event.ResumeParseRequestedEvent;
import com.interviewcoach.resume.domain.entity.Resume;
import com.interviewcoach.resume.domain.repository.ResumeRepository;
import com.interviewcoach.resume.infrastructure.redis.ResumeAiTaskAdmissionService;
import com.interviewcoach.resume.infrastructure.redis.ResumeAiTaskLeaseRunner;
import com.interviewcoach.resume.infrastructure.redis.ResumeAiTaskProperties;
import com.interviewcoach.resume.infrastructure.redis.ResumeDailyCreationQuotaService;
import com.interviewcoach.resume.infrastructure.redis.ResumeDailyCreationQuotaService.CreationReservation;
import com.interviewcoach.resume.infrastructure.redis.ResumeUserMutationLock;
import com.interviewcoach.resume.infrastructure.storage.FileStorageService;
import com.interviewcoach.user.application.service.ConsentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * 编排上传数量限制、文件落盘、数据库短事务、AI 准入和跨资源失败补偿。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ResumeUploadService {

    private final ResumeRepository resumeRepository;
    private final ResumePersistenceService persistenceService;
    private final FileStorageService fileStorageService;
    private final ConsentService consentService;
    private final ResumeUserMutationLock mutationLock;
    private final ResumeDailyCreationQuotaService creationQuotaService;
    private final ResumeAiTaskAdmissionService admissionService;
    private final ResumeAiTaskLeaseRunner leaseRunner;
    private final ResumeAiTaskProperties taskProperties;
    private final ApplicationEventPublisher eventPublisher;

    @Value("${resume.upload.max-size:10485760}")
    private long maxFileSize;

    public ResumeUploadResponse upload(Long userId, MultipartFile file, String fileType) {
        validateFile(file, fileType);
        consentService.requireAiProcessingConsent(userId);
        CreationReservation creationReservation = creationQuotaService.reserve(userId);
        UploadResources resources = new UploadResources();
        try {
            mutationLock.executeChecked(userId, () -> {
                prepareUnderUserLock(userId, file, fileType, creationReservation, resources);
                return null;
            });
        } catch (RuntimeException e) {
            compensateFailedUpload(userId, creationReservation, resources);
            throw e;
        }

        ResumeParseRequestedEvent event = new ResumeParseRequestedEvent(
                resources.resume.getId(),
                userId,
                resources.resume.getParseGeneration(),
                false,
                resources.taskLease,
                null);
        try {
            eventPublisher.publishEvent(event);
        } catch (RuntimeException e) {
            compensateFailedUpload(userId, creationReservation, resources);
            throw new BusinessException(
                    ResumeErrorCode.RESUME_TASK_INFRASTRUCTURE_UNAVAILABLE,
                    "解析任务登记失败，请稍后重试",
                    e);
        }
        return toUploadResponse(resources.resume);
    }

    private void prepareUnderUserLock(
            Long userId,
            MultipartFile file,
            String fileType,
            CreationReservation creationReservation,
            UploadResources resources) {
        if (resumeRepository.countByUserId(userId) >= taskProperties.getRetainedResumes()) {
            throw new BusinessException(ResumeErrorCode.RETAINED_RESUME_LIMIT, "当前保留简历数量已达上限");
        }
        resources.filePath = fileStorageService.store(userId, file);
        resources.resume = new Resume();
        persistenceService.createPending(
                resources.resume,
                userId,
                file.getOriginalFilename(),
                resources.filePath,
                fileType,
                file.getSize());
        resources.taskLease = admissionService.acquire(userId, resources.resume.getId());
        if (!creationQuotaService.commit(userId, creationReservation)) {
            throw new BusinessException(
                    ResumeErrorCode.RESUME_TASK_INFRASTRUCTURE_UNAVAILABLE,
                    "简历新建额度状态异常，请稍后重试");
        }
    }

    private void compensateFailedUpload(
            Long userId, CreationReservation creationReservation, UploadResources resources) {
        if (resources.taskLease != null && resources.resume != null) {
            try {
                leaseRunner.releaseWithoutRun(userId, resources.resume.getId(), resources.taskLease);
            } catch (RuntimeException e) {
                log.warn("[ResumeUpload] AI 任务许可补偿失败，继续数据库补偿: userId={}, resumeId={}, errorType={}",
                        userId, resources.resume.getId(), e.getClass().getSimpleName());
            }
        }
        Long resumeId = resources.resume == null ? null : resources.resume.getId();
        boolean databaseCompensationConfirmed = resumeId == null;
        if (!databaseCompensationConfirmed) {
            try {
                persistenceService.deleteCreatedForCompensation(userId, resumeId);
                databaseCompensationConfirmed = true;
            } catch (RuntimeException e) {
                log.error("[ResumeUpload] 数据库创建补偿未确认，保留新建额度: userId={}, resumeId={}, errorType={}",
                        userId, resumeId, e.getClass().getSimpleName(), e);
            }
        }
        // 数据库结果未知时保持 fail-closed，避免残留简历与已返还额度同时成立。
        if (databaseCompensationConfirmed) {
            try {
                if (!creationQuotaService.release(userId, creationReservation)) {
                    log.warn("[ResumeUpload] 新建额度补偿状态不匹配: userId={}, resumeId={}",
                            userId, resumeId);
                }
            } catch (RuntimeException e) {
                log.warn("[ResumeUpload] 新建额度预留补偿失败: userId={}, resumeId={}, errorType={}",
                        userId, resumeId, e.getClass().getSimpleName());
            }
        }
        if (resources.filePath != null) {
            fileStorageService.delete(resources.filePath);
        }
    }

    private void validateFile(MultipartFile file, String fileType) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ResumeErrorCode.FILE_READ_FAILED, "文件为空");
        }
        if (file.getSize() > maxFileSize) {
            throw new BusinessException(ResumeErrorCode.FILE_SIZE_EXCEEDED, "文件大小超过10MB限制");
        }
        String type = fileType == null ? "" : fileType.toUpperCase();
        if (!type.equals("PDF") && !type.equals("TXT")) {
            throw new BusinessException(ResumeErrorCode.FILE_TYPE_NOT_SUPPORTED, "仅支持 PDF 和 TXT 格式");
        }
    }

    private ResumeUploadResponse toUploadResponse(Resume resume) {
        return new ResumeUploadResponse(
                resume.getId(), resume.getResumeName(), resume.getParseStatus().name(),
                resume.getParseStatus().getDisplayName(), 10);
    }

    private static final class UploadResources {
        private String filePath;
        private Resume resume;
        private ResumeAiTaskLease taskLease;
    }
}
