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
 * 接收简历应用服务的上传请求，依次协调用户同意、每日创建额度、同用户变更锁、文件落盘、
 * 数据库 PENDING 记录、AI 许可和进程内事件交接；任一步失败时按已取得资源逆序尽力补偿。
 * 文件、数据库与 Redis 不共享事务，本服务通过资源记录和失败关闭降低跨资源不一致风险。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ResumeUploadService {

    /** 在同用户锁内统计当前保留简历数量的仓储。 */
    private final ResumeRepository resumeRepository;
    /** 创建 PENDING 简历并在失败时删除本次记录的数据库短事务服务。 */
    private final ResumePersistenceService persistenceService;
    /** 保存上传内容并在补偿时删除服务端文件的存储服务。 */
    private final FileStorageService fileStorageService;
    /** 在任何文件或 AI 资源占用前校验用户处理同意的服务。 */
    private final ConsentService consentService;
    /** 串行同一用户上传、删除等简历资源变更的 Redis 锁。 */
    private final ResumeUserMutationLock mutationLock;
    /** 预留、提交或退回用户每日简历创建次数的 Redis 服务。 */
    private final ResumeDailyCreationQuotaService creationQuotaService;
    /** 为新简历的事实解析申请同用户、同简历 AI 任务许可的服务。 */
    private final ResumeAiTaskAdmissionService admissionService;
    /** 在任务尚未进入 Worker 时释放已取得 AI 许可的执行器。 */
    private final ResumeAiTaskLeaseRunner leaseRunner;
    /** 提供每用户保留简历数量等任务限制的配置。 */
    private final ResumeAiTaskProperties taskProperties;
    /** 将已登记的解析任务交给事务后监听器和后台 Worker 的事件发布器。 */
    private final ApplicationEventPublisher eventPublisher;

    /**
     * 单个上传文件的最大字节数，默认 10 MiB（10485760 字节），由 resume.upload.max-size 覆盖。
     * 默认精确取值的产品或容量依据缺失；调大会增加存储、文本提取与内存压力，调小会拒绝原可接受文件。
     */
    @Value("${resume.upload.max-size:10485760}")
    private long maxFileSize;

    /**
     * 完成文件校验、AI 同意、创建额度、资源创建和解析事件交接，返回可轮询的 PENDING 简历。
     * 发布前异常会补偿已取得资源；发布成功后资源责任转交事件监听器和 Worker。
     */
    public ResumeUploadResponse upload(Long userId, MultipartFile file, String fileType) {
        // 先在本地验证空文件、大小和公开类型，非法输入不会占用用户或基础设施资源。
        validateFile(file, fileType);
        // 原文件内容将进入提取与模型链路，落盘前要求用户已有 AI 处理同意。
        consentService.requireAiProcessingConsent(userId);
        // 先预留当天创建次数；只有锁内全部资源准备成功后才提交该预留。
        CreationReservation creationReservation = creationQuotaService.reserve(userId);
        UploadResources resources = new UploadResources();
        try {
            // 在同用户变更锁内检查保留数量并创建文件、数据库记录、AI 许可，避免上传/删除竞态。
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
            // 数据库和额度已提交后发布事件；监听器接管许可并将解析交给后台 Worker。
            eventPublisher.publishEvent(event);
        } catch (RuntimeException e) {
            // 事件未成功交接时仍由本服务逆向释放许可、数据库记录、额度和文件。
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
        // 锁内重新统计用户保留简历，达到配置上限时不创建文件或数据库记录。
        if (resumeRepository.countByUserId(userId) >= taskProperties.getRetainedResumes()) {
            throw new BusinessException(ResumeErrorCode.RETAINED_RESUME_LIMIT, "当前保留简历数量已达上限");
        }
        // 文件先落盘并记录路径；后续任何失败都由 UploadResources 驱动删除补偿。
        resources.filePath = fileStorageService.store(userId, file);
        resources.resume = new Resume();
        // 独立短事务创建 PENDING 简历并得到 ID，文件路径成为数据库记录的一部分。
        persistenceService.createPending(
                resources.resume,
                userId,
                file.getOriginalFilename(),
                resources.filePath,
                fileType,
                file.getSize());
        // 有简历 ID 后申请 AI 许可，许可会随事件交给监听器或由补偿路径释放。
        resources.taskLease = admissionService.acquire(userId, resources.resume.getId());
        // 所有锁内资源准备完成后提交每日创建预留；状态不匹配按基础设施失败处理。
        if (!creationQuotaService.commit(userId, creationReservation)) {
            throw new BusinessException(
                    ResumeErrorCode.RESUME_TASK_INFRASTRUCTURE_UNAVAILABLE,
                    "简历新建额度状态异常，请稍后重试");
        }
    }

    /**
     * 按许可、数据库记录、每日创建额度、物理文件的顺序尽力回收上传资源。
     * 数据库删除结果未知时不退回创建额度，避免残留简历与可重复使用额度同时成立；文件删除仍会尝试执行。
     */
    private void compensateFailedUpload(
            Long userId, CreationReservation creationReservation, UploadResources resources) {
        if (resources.taskLease != null && resources.resume != null) {
            try {
                // Worker 尚未接管时直接释放许可；释放失败不阻断后续数据库和文件补偿。
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
                // 按用户归属删除本次已创建记录；重复补偿时不存在也视为已确认清理。
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
                // 只有数据库已确认不存在本次记录时才退回每日创建预留。
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
            // 文件系统不受数据库或 Redis 事务保护，最后按已记录路径尽力删除。
            fileStorageService.delete(resources.filePath);
        }
    }

    /** 校验上传文件非空、不超过配置字节数且类型为 PDF/TXT；不读取文件正文。 */
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

    /**
     * 将已交接的 PENDING 简历映射为上传响应，固定进度 10 仅表示任务已登记。
     * 精确取值 10 的产品依据缺失；调整会改变客户端初始展示，不代表 Worker 实时进度。
     */
    private ResumeUploadResponse toUploadResponse(Resume resume) {
        return new ResumeUploadResponse(
                resume.getId(), resume.getResumeName(), resume.getParseStatus().name(),
                resume.getParseStatus().getDisplayName(), 10);
    }

    /**
     * 单次上传在跨文件、数据库和 Redis 边界上已取得的资源清单，仅在当前请求内存中使用。
     */
    private static final class UploadResources {
        /** 已成功落盘、失败时需要删除的服务端文件路径；尚未落盘时为 null。 */
        private String filePath;
        /** 已尝试持久化的本次简历实体；可能携带用于补偿确认的数据库 ID。 */
        private Resume resume;
        /** 已取得且尚未交给事件监听器的 AI 任务许可；未取得时为 null。 */
        private ResumeAiTaskLease taskLease;
    }
}
