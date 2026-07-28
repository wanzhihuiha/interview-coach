package com.interviewcoach.position.infrastructure.storage;

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
 * JD 文件存储服务。
 */
@Slf4j
@Service
public class JdFileStorageService {

    private final Path storagePath;

    public JdFileStorageService(@Value("${position.upload.storage-path:./uploads/positions}") String storagePath) {
        this.storagePath = Paths.get(storagePath).toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.storagePath);
        } catch (IOException e) {
            throw new RuntimeException("无法创建 JD 存储目录: " + this.storagePath, e);
        }
    }

    /**
     * 保存用户上传的 JD 文件。
     */
    public String store(Long userId, MultipartFile file) {
        String originalName = file.getOriginalFilename();
        String extension = getExtension(originalName);
        String fileName = UUID.randomUUID() + "_" + System.currentTimeMillis() + extension;
        Path userDir = storagePath.resolve(String.valueOf(userId));
        try {
            Files.createDirectories(userDir);
            Path target = userDir.resolve(fileName);
            try (InputStream inputStream = file.getInputStream()) {
                Files.copy(inputStream, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return target.toString();
        } catch (IOException e) {
            log.error("保存 JD 文件失败: userId={}, fileName={}", userId, originalName, e);
            throw new RuntimeException("保存 JD 文件失败", e);
        }
    }

    private String getExtension(String fileName) {
        if (fileName == null || !fileName.contains(".")) {
            return "";
        }
        return fileName.substring(fileName.lastIndexOf("."));
    }
}
