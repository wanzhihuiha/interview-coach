package com.interviewcoach.resume.infrastructure.storage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * 本地文件系统简历存储服务，按用户 ID 隔离目录并返回绝对路径。
 */
@Slf4j
@Service
public class FileStorageService {

    /** 配置的本地简历存储根目录，初始化时转换为绝对规范路径；是否跨实例共享无法由源码确认。 */
    private final Path storagePath;

    /**
     * 在 Bean 初始化时规范化并创建存储根目录；目录不可创建时阻止应用继续启动。
     */
    public FileStorageService(@Value("${resume.upload.storage-path:./uploads/resumes}") String storagePath) {
        this.storagePath = Paths.get(storagePath).toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.storagePath);
        } catch (IOException e) {
            throw new RuntimeException("无法创建简历存储目录: " + this.storagePath, e);
        }
    }

    /**
     * 保存用户上传的简历文件。
     * 文件系统写入不参与调用方数据库事务；后续数据库写入失败时，已写文件不会自动回滚。
     *
     * @param userId 用户 ID
     * @param file   上传文件
     * @return 已保存文件的绝对路径
     */
    public String store(Long userId, MultipartFile file) {
        String originalName = file.getOriginalFilename();
        String extension = getExtension(originalName);
        String fileName = UUID.randomUUID() + "_" + System.currentTimeMillis() + extension;
        Path userDir = storagePath.resolve(String.valueOf(userId));
        try {
            // 先创建用户隔离目录，再把上传流复制到服务端生成的文件名；成功返回供数据库登记的绝对路径。
            Files.createDirectories(userDir);
            Path target = userDir.resolve(fileName);
            try (InputStream inputStream = file.getInputStream()) {
                Files.copy(inputStream, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return target.toString();
        } catch (IOException e) {
            log.warn("[ResumeStorage] 保存简历文件失败: userId={}, fileSize={}, errorType={}",
                    userId, file.getSize(), e.getClass().getSimpleName());
            throw new RuntimeException("保存简历文件失败: " + e.getClass().getSimpleName());
        }
    }

    /**
     * 尽力删除简历文件；路径为空时直接跳过，I/O 删除失败只记录安全摘要且不阻断数据库删除。
     */
    public void delete(String filePath) {
        if (filePath == null || filePath.isBlank()) {
            return;
        }
        try {
            Files.deleteIfExists(Path.of(filePath));
        } catch (IOException e) {
            log.warn("[ResumeStorage] 删除简历文件失败: errorType={}",
                    e.getClass().getSimpleName());
        }
    }

    /** 保留原始文件名最后一个点及其后缀；无后缀时返回空字符串。 */
    private String getExtension(String fileName) {
        if (fileName == null || !fileName.contains(".")) {
            return "";
        }
        return fileName.substring(fileName.lastIndexOf("."));
    }
}
