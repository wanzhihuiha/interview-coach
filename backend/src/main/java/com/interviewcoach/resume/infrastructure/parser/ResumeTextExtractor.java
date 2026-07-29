package com.interviewcoach.resume.infrastructure.parser;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

/**
 * 简历文本提取器，支持 PDF 与 TXT 格式。
 */
@Slf4j
@Component
public class ResumeTextExtractor {

    /**
     * 从上传文件中提取纯文本内容。
     *
     * @param file     上传文件
     * @param fileType 文件类型：PDF / TXT
     * @return 提取的纯文本
     * @throws IllegalArgumentException 文件类型不受支持或文件无法读取
     */
    public String extract(MultipartFile file, String fileType) {
        try {
            return switch (fileType.toUpperCase()) {
                case "PDF" -> extractFromPdf(file.getInputStream());
                case "TXT" -> extractFromTxt(file.getInputStream());
                default -> throw new IllegalArgumentException("不支持的文件类型: " + fileType);
            };
        } catch (IOException e) {
            log.warn("[ResumeText] 上传文件文本提取失败: fileType={}, errorType={}",
                    fileType, e.getClass().getSimpleName());
            throw new IllegalArgumentException("文件读取失败: " + e.getClass().getSimpleName());
        }
    }

    /**
     * 从已持久化的文件路径中提取纯文本，供后台简历解析任务使用。
     *
     * @param filePath 文件绝对路径
     * @param fileType 文件类型：PDF / TXT
     * @return 提取的纯文本
     * @throws IllegalArgumentException 文件不存在、类型不受支持或文件无法读取
     */
    public String extractFromFile(String filePath, String fileType) {
        Path path;
        try {
            path = Path.of(filePath);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("简历文件路径无效: " + e.getClass().getSimpleName());
        }
        if (!Files.exists(path)) {
            throw new IllegalArgumentException("简历文件不存在");
        }
        try {
            return switch (fileType.toUpperCase()) {
                case "PDF" -> extractFromPdf(Files.newInputStream(path));
                case "TXT" -> extractFromTxt(Files.newInputStream(path));
                default -> throw new IllegalArgumentException("不支持的文件类型: " + fileType);
            };
        } catch (IOException e) {
            // 后台工作器会用 resumeId 和处理阶段统一记录终态错误，这里不重复输出文件路径。
            throw new IllegalArgumentException("文件读取失败: " + e.getClass().getSimpleName());
        }
    }

    private String extractFromPdf(InputStream inputStream) throws IOException {
        try (inputStream; PDDocument document = Loader.loadPDF(inputStream.readAllBytes())) {
            PDFTextStripper stripper = new PDFTextStripper();
            return stripper.getText(document).trim();
        }
    }

    private String extractFromTxt(InputStream inputStream) throws IOException {
        try (inputStream) {
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8).trim();
        }
    }
}
