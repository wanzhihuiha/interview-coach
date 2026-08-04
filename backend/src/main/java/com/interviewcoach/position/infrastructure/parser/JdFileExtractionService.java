package com.interviewcoach.position.infrastructure.parser;

import com.interviewcoach.common.exception.BusinessException;
import com.interviewcoach.position.application.service.JdContentNormalizer;
import com.interviewcoach.position.application.service.PositionErrorCode;
import com.interviewcoach.position.infrastructure.config.PositionTaskExecutorConfiguration;
import com.interviewcoach.position.infrastructure.config.PositionUploadProperties;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * 将上传流移交给有界提取 Worker；Worker 是临时文件清理和提取槽位的唯一责任人。
 */
@Slf4j
@Service
public class JdFileExtractionService {

    private static final String TEMP_FILE_PREFIX = "position-jd-";
    private static final int COPY_BUFFER_SIZE = 8192;

    private final ExecutorService executor;
    private final PositionUploadProperties properties;
    private final JdTextExtractor textExtractor;
    private final JdContentNormalizer contentNormalizer;
    private Path tempDirectory;

    public JdFileExtractionService(
            @Qualifier(PositionTaskExecutorConfiguration.FILE_EXTRACTION_EXECUTOR)
            ExecutorService executor,
            PositionUploadProperties properties,
            JdTextExtractor textExtractor,
            JdContentNormalizer contentNormalizer) {
        this.executor = executor;
        this.properties = properties;
        this.textExtractor = textExtractor;
        this.contentNormalizer = contentNormalizer;
    }

    /**
     * 只清理本模块目录内带明确前缀且超过年龄阈值的孤儿文件。
     */
    @PostConstruct
    public void initializeTemporaryDirectory() {
        tempDirectory = Path.of(properties.getTempDirectory()).toAbsolutePath().normalize();
        try {
            Files.createDirectories(tempDirectory);
            cleanupOrphans();
        } catch (IOException e) {
            throw new IllegalStateException("无法初始化岗位 JD 临时目录", e);
        }
    }

    /**
     * 请求超时或中断只停止等待；已经接管的 Worker 仍会运行到真实结束并在 finally 清理。
     */
    public String extract(MultipartFile file, String declaredFileType) {
        JdFileType fileType = JdFileType.from(declaredFileType);
        validateMultipart(file);

        Path temporaryFile = createTemporaryFile(fileType);
        boolean workerOwnsFile = false;
        try {
            copyWithActualSizeLimit(file, temporaryFile);
            AtomicBoolean detachedRequest = new AtomicBoolean(false);
            Future<String> future;
            try {
                future = executor.submit(() -> extractAndCleanup(
                        temporaryFile, fileType, detachedRequest));
                workerOwnsFile = true;
            } catch (RejectedExecutionException e) {
                throw new BusinessException(
                        PositionErrorCode.FILE_EXTRACTION_BUSY,
                        "文件提取资源繁忙，请稍后重试");
            }

            try {
                return future.get(
                        properties.getExtractionTimeout().toNanos(), TimeUnit.NANOSECONDS);
            } catch (TimeoutException e) {
                detachedRequest.set(true);
                throw new BusinessException(
                        PositionErrorCode.FILE_EXTRACTION_TIMEOUT,
                        "文件提取超时，请稍后重试");
            } catch (InterruptedException e) {
                detachedRequest.set(true);
                Thread.currentThread().interrupt();
                throw new BusinessException(
                        PositionErrorCode.FILE_EXTRACTION_INTERRUPTED,
                        "文件提取已中断，请重试");
            } catch (ExecutionException e) {
                throw mapExtractionFailure(e.getCause());
            }
        } finally {
            if (!workerOwnsFile) {
                deleteTemporaryFile(temporaryFile);
            }
        }
    }

    private String extractAndCleanup(
            Path temporaryFile, JdFileType fileType, AtomicBoolean detachedRequest) {
        try {
            String extracted = textExtractor.extract(
                    temporaryFile,
                    fileType,
                    properties.getMaxPdfPages(),
                    properties.getMaxCodePoints());
            return contentNormalizer.normalizeAndValidate(
                    extracted, properties.getMaxCodePoints());
        } catch (RuntimeException e) {
            if (detachedRequest.get()) {
                log.warn("[PositionUpload] 请求结束后提取 Worker 失败: "
                                + "fileType={}, errorType={}",
                        fileType, e.getClass().getSimpleName());
            }
            throw e;
        } finally {
            deleteTemporaryFile(temporaryFile);
        }
    }

    private RuntimeException mapExtractionFailure(Throwable cause) {
        if (cause instanceof BusinessException businessException) {
            return businessException;
        }
        log.error("[PositionUpload] 文件提取出现未预期异常: errorType={}",
                cause == null ? "unknown" : cause.getClass().getSimpleName(), cause);
        return new BusinessException(PositionErrorCode.FILE_READ_FAILED, "文件读取失败");
    }

    private void validateMultipart(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(PositionErrorCode.FILE_READ_FAILED, "文件为空");
        }
        if (file.getSize() > properties.getMaxSize()) {
            throw new BusinessException(
                    PositionErrorCode.FILE_SIZE_EXCEEDED,
                    "文件大小超过 " + properties.getMaxSize() + " 字节限制");
        }
    }

    private Path createTemporaryFile(JdFileType fileType) {
        try {
            return Files.createTempFile(
                    tempDirectory, TEMP_FILE_PREFIX, fileType.temporarySuffix());
        } catch (IOException e) {
            log.error("[PositionUpload] 创建临时文件失败: errorType={}",
                    e.getClass().getSimpleName(), e);
            throw new BusinessException(
                    PositionErrorCode.FILE_READ_FAILED, "无法创建文件提取任务", e);
        }
    }

    private void copyWithActualSizeLimit(MultipartFile file, Path target) {
        long totalBytes = 0L;
        byte[] buffer = new byte[COPY_BUFFER_SIZE];
        try (InputStream input = file.getInputStream();
                OutputStream output = Files.newOutputStream(target)) {
            int read;
            while ((read = input.read(buffer)) != -1) {
                if (read > properties.getMaxSize() - totalBytes) {
                    throw new BusinessException(
                            PositionErrorCode.FILE_SIZE_EXCEEDED,
                            "文件实际大小超过 " + properties.getMaxSize() + " 字节限制");
                }
                output.write(buffer, 0, read);
                totalBytes += read;
            }
        } catch (BusinessException e) {
            throw e;
        } catch (IOException e) {
            log.error("[PositionUpload] 写入临时文件失败: errorType={}",
                    e.getClass().getSimpleName(), e);
            throw new BusinessException(PositionErrorCode.FILE_READ_FAILED, "文件读取失败", e);
        }
        if (totalBytes == 0L) {
            throw new BusinessException(PositionErrorCode.FILE_READ_FAILED, "文件为空");
        }
    }

    private void cleanupOrphans() throws IOException {
        Instant cutoff = Instant.now().minus(properties.getOrphanMaxAge());
        int removed = 0;
        try (Stream<Path> files = Files.list(tempDirectory)) {
            for (Path candidate : files.toList()) {
                if (!isOwnedTemporaryFile(candidate) || !Files.isRegularFile(candidate)) {
                    continue;
                }
                FileTime lastModified = Files.getLastModifiedTime(candidate);
                if (lastModified.toInstant().isBefore(cutoff) && deleteTemporaryFile(candidate)) {
                    removed++;
                }
            }
        }
        if (removed > 0) {
            log.info("[PositionUpload] 已清理岗位 JD 孤儿临时文件: count={}", removed);
        }
    }

    private boolean deleteTemporaryFile(Path file) {
        if (!isOwnedTemporaryFile(file)) {
            log.error("[PositionUpload] 拒绝清理非本模块临时文件");
            return false;
        }
        try {
            return Files.deleteIfExists(file);
        } catch (IOException e) {
            log.warn("[PositionUpload] 临时文件清理失败: fileName={}, errorType={}",
                    file.getFileName(), e.getClass().getSimpleName());
            return false;
        }
    }

    private boolean isOwnedTemporaryFile(Path file) {
        if (file == null || tempDirectory == null) {
            return false;
        }
        Path normalized = file.toAbsolutePath().normalize();
        return tempDirectory.equals(normalized.getParent())
                && normalized.getFileName().toString().startsWith(TEMP_FILE_PREFIX);
    }
}
