package com.interviewcoach.position.infrastructure.parser;

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
 * JD 文本提取器，支持 PDF 与 TXT 格式。
 */
@Slf4j
@Component
public class JdTextExtractor {

    /**
     * 从上传文件中提取纯文本内容。
     */
    public String extract(MultipartFile file, String fileType) {
        try {
            return switch (fileType.toUpperCase()) {
                case "PDF" -> extractFromPdf(file.getInputStream());
                case "TXT" -> extractFromTxt(file.getInputStream());
                default -> throw new IllegalArgumentException("不支持的文件类型: " + fileType);
            };
        } catch (IOException e) {
            log.error("JD 文本提取失败: fileType={}", fileType, e);
            throw new IllegalArgumentException("文件读取失败", e);
        }
    }

    /**
     * 从已存储的文件路径中提取纯文本内容。
     */
    public String extractFromFile(String filePath, String fileType) {
        Path path = Path.of(filePath);
        if (!Files.exists(path)) {
            throw new IllegalArgumentException("JD 文件不存在: " + filePath);
        }
        try {
            return switch (fileType.toUpperCase()) {
                case "PDF" -> extractFromPdf(Files.newInputStream(path));
                case "TXT" -> extractFromTxt(Files.newInputStream(path));
                default -> throw new IllegalArgumentException("不支持的文件类型: " + fileType);
            };
        } catch (IOException e) {
            log.error("JD 文本提取失败: filePath={}, fileType={}", filePath, fileType, e);
            throw new IllegalArgumentException("文件读取失败", e);
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
