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
 * 将上传流或文件服务已落盘的 PDF/TXT 简历转换为纯文本，供事实解析 Worker 后续交给 Agent 脱敏并发送模型。
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
            // 根据已校验的文件类型选择解析分支；PDF/TXT 都会完整读入内存后提取。
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
            // 后台任务从数据库保存的路径重新打开文件，成功后返回去除首尾空白的全文。
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

    /** 读取整个 PDF 字节并用 PDFBox 提取文本，关闭输入流和文档。 */
    private String extractFromPdf(InputStream inputStream) throws IOException {
        try (inputStream; PDDocument document = Loader.loadPDF(inputStream.readAllBytes())) {
            PDFTextStripper stripper = new PDFTextStripper();
            return stripper.getText(document).trim();
        }
    }

    /** 按 UTF-8 读取整个 TXT 流并去除首尾空白。 */
    private String extractFromTxt(InputStream inputStream) throws IOException {
        try (inputStream) {
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8).trim();
        }
    }
}
