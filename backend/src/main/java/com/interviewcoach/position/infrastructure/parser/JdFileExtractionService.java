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
 * 岗位上传文件的请求侧编排服务：限制实际字节、写入模块临时目录，并将文本提取交给有界 Worker。
 * 执行器接管前由请求线程负责清理，接管后 Worker 是临时文件和提取槽位的唯一责任人；原文件不进入岗位持久化。
 * 默认临时目录是否跨实例共享取决于部署，当前证据不足。
 */
@Slf4j
@Service
public class JdFileExtractionService {

    /** 本模块随机临时文件的固定前缀，用于限制启动清理和删除范围。 */
    private static final String TEMP_FILE_PREFIX = "position-jd-";
    /** 流式复制上传内容的当前固定缓冲区大小，单位为字节；8192 的精确依据缺失。 */
    private static final int COPY_BUFFER_SIZE = 8192;

    /** 承载受限文本提取并在结束时清理临时文件的有界执行器。 */
    private final ExecutorService executor;
    /** 提供字节、字符、页数、超时、目录和清理年龄限制的上传配置。 */
    private final PositionUploadProperties properties;
    /** 按真实 PDF/TXT 内容提取受限 Unicode 文本的组件。 */
    private final JdTextExtractor textExtractor;
    /** 对提取结果统一执行 BOM、换行、首尾空白和最终长度校验的组件。 */
    private final JdContentNormalizer contentNormalizer;
    /** 规范化后的本模块临时目录绝对路径，在启动回调中创建并保存。 */
    private Path tempDirectory;

    /**
     * 装配文件提取链路的执行器、限制配置、格式提取器和文本规范化器。
     */
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
            // 先保证配置目录存在，再只扫描本目录中属于岗位模块且超过年龄阈值的文件。
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
        // 声明类型只决定受支持的提取分支和临时后缀，实际内容仍由提取器重新校验。
        JdFileType fileType = JdFileType.from(declaredFileType);
        validateMultipart(file);

        // 使用受控目录、固定前缀和服务端随机名创建临时文件，不采信客户端文件名。
        Path temporaryFile = createTemporaryFile(fileType);
        boolean workerOwnsFile = false;
        try {
            // 流式复制并按实际读取字节再次限制大小，不能只信 Multipart 元数据。
            copyWithActualSizeLimit(file, temporaryFile);
            AtomicBoolean detachedRequest = new AtomicBoolean(false);
            Future<String> future;
            try {
                // 提交成功后把提取和删除责任一次性交给 Worker；请求线程不再提前删除该文件。
                future = executor.submit(() -> extractAndCleanup(
                        temporaryFile, fileType, detachedRequest));
                workerOwnsFile = true;
            } catch (RejectedExecutionException e) {
                throw new BusinessException(
                        PositionErrorCode.FILE_EXTRACTION_BUSY,
                        "文件提取资源繁忙，请稍后重试");
            }

            try {
                // 请求仅等待配置时长；等待结束不取消仍持有文件和执行槽位的 Worker。
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
                // 创建、复制或执行器提交失败时 Worker 尚未接管，由请求线程清理临时文件。
                deleteTemporaryFile(temporaryFile);
            }
        }
    }

    private String extractAndCleanup(
            Path temporaryFile, JdFileType fileType, AtomicBoolean detachedRequest) {
        try {
            // Worker 按真实格式、PDF 页数和 code point 上限提取文本，不读取客户端原文件名。
            String extracted = textExtractor.extract(
                    temporaryFile,
                    fileType,
                    properties.getMaxPdfPages(),
                    properties.getMaxCodePoints());
            // 再使用粘贴文本相同的规范化契约校验空内容和最终长度，结果交给岗位登记流程。
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
            // 无论提取成功、业务拒绝、请求超时或未预期异常，接管后的 Worker 最终都尝试删除文件。
            deleteTemporaryFile(temporaryFile);
        }
    }

    /** 将 Worker 业务异常原样传播，并把未预期异常收口为不暴露内部信息的文件读取失败。 */
    private RuntimeException mapExtractionFailure(Throwable cause) {
        if (cause instanceof BusinessException businessException) {
            return businessException;
        }
        log.error("[PositionUpload] 文件提取出现未预期异常: errorType={}",
                cause == null ? "unknown" : cause.getClass().getSimpleName(), cause);
        return new BusinessException(PositionErrorCode.FILE_READ_FAILED, "文件读取失败");
    }

    /** 先按 Multipart 可见状态快速拒绝空文件或声明大小超过配置上限的请求。 */
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

    /** 在本模块目录创建固定前缀、随机名称和受控后缀的临时文件。 */
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

    /** 流式复制上传内容，并以实际读到的字节数执行最终大小限制和空文件检查。 */
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

    /** 启动时删除本模块目录内、固定前缀且修改时间早于配置阈值的普通文件。 */
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

    /** 仅删除通过本模块目录与前缀双重归属校验的文件；失败记录告警并返回 {@code false}。 */
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

    /** 判断规范化路径是否直接位于本模块临时目录，且文件名带固定岗位前缀。 */
    private boolean isOwnedTemporaryFile(Path file) {
        if (file == null || tempDirectory == null) {
            return false;
        }
        Path normalized = file.toAbsolutePath().normalize();
        return tempDirectory.equals(normalized.getParent())
                && normalized.getFileName().toString().startsWith(TEMP_FILE_PREFIX);
    }
}
