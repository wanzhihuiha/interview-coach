package com.interviewcoach.position.infrastructure.parser;

import com.interviewcoach.common.exception.BusinessException;
import com.interviewcoach.position.application.service.PositionErrorCode;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Writer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

/**
 * 从服务端岗位临时文件按真实 PDF/TXT 特征提取受限、规范化的 JD 文本。
 * 文件服务创建并最终清理临时文件，本组件只读取内容，不持有路径、不保存上传原文件。
 */
@Slf4j
@Component
public class JdTextExtractor {

    /** PDF 文件头的标准 ASCII 签名字节，用于拒绝声明类型与实际内容不符的上传。 */
    private static final byte[] PDF_SIGNATURE = "%PDF-".getBytes(StandardCharsets.US_ASCII);
    /** 当前在文件前部扫描 PDF 签名的字节数；1024 的精确依据缺失，调大增加预读，调小可能缩小可识别窗口。 */
    private static final int SIGNATURE_SCAN_BYTES = 1024;

    /**
     * 校验真实文件特征并提取最多 {@code maxCodePoints} 个 Unicode code point，按失败类型映射稳定业务错误。
     *
     * @param path 文件服务创建的受控临时文件
     * @param fileType HTTP 声明经服务端转换后的受支持类型
     * @param maxPdfPages PDF 最大允许页数
     * @param maxCodePoints 规范化文本最大 Unicode code point 数
     * @return 已统一换行、去除开头 BOM 和首尾空白的提取文本
     */
    public String extract(Path path, JdFileType fileType, int maxPdfPages, int maxCodePoints) {
        try {
            // 声明类型选择解析器；每个分支仍验证真实内容特征并在读取期间执行长度上限。
            return switch (fileType) {
                case PDF -> extractPdf(path, maxPdfPages, maxCodePoints);
                case TXT -> extractTxt(path, maxCodePoints);
            };
        } catch (TextLimitExceededException e) {
            throw new BusinessException(
                    PositionErrorCode.JD_CONTENT_TOO_LONG,
                    "JD 描述不能超过 " + maxCodePoints + " 个字符");
        } catch (InvalidPasswordException e) {
            throw new BusinessException(
                    PositionErrorCode.PDF_ENCRYPTED, "不支持加密 PDF 文件");
        } catch (CharacterCodingException e) {
            throw new BusinessException(
                    PositionErrorCode.FILE_CONTENT_INVALID, "TXT 文件必须使用 UTF-8 编码");
        } catch (IOException e) {
            log.error("[PositionUpload] JD 提取失败: fileType={}, errorType={}",
                    fileType, e.getClass().getSimpleName(), e);
            throw new BusinessException(
                    PositionErrorCode.FILE_READ_FAILED, "文件读取失败", e);
        }
    }

    /** 校验 PDF 签名、加密状态和页数，再通过 PDFBox 把文本流入 code point 限制 Writer。 */
    private String extractPdf(Path path, int maxPdfPages, int maxCodePoints) throws IOException {
        // 在受限前部扫描标准签名，避免仅凭请求参数把任意内容交给 PDFBox。
        if (!hasPdfSignature(path)) {
            throw new BusinessException(
                    PositionErrorCode.FILE_CONTENT_INVALID, "文件实际内容不是 PDF");
        }
        // PDFBox 加载后再次检查加密和页数，任何失败都不会产生部分成功文本。
        try (PDDocument document = Loader.loadPDF(path.toFile())) {
            if (document.isEncrypted()) {
                throw new BusinessException(
                        PositionErrorCode.PDF_ENCRYPTED, "不支持加密 PDF 文件");
            }
            if (document.getNumberOfPages() > maxPdfPages) {
                throw new BusinessException(
                        PositionErrorCode.PDF_PAGE_LIMIT_EXCEEDED,
                        "PDF 页数不能超过 " + maxPdfPages + " 页");
            }
            NormalizedLimitedTextWriter writer = new NormalizedLimitedTextWriter(maxCodePoints);
            // PDFBox 按页输出到受限 Writer；超限立即抛出，不先在堆中累积完整文档。
            new PDFTextStripper().writeText(document, writer);
            return writer.text();
        }
    }

    /** 严格按 UTF-8 流式读取 TXT，拒绝 PDF 签名和二进制控制字符后写入受限 Writer。 */
    private String extractTxt(Path path, int maxCodePoints) throws IOException {
        // TXT 声明不能掩盖实际 PDF 内容，避免绕过 PDF 的页数与加密检查。
        if (hasPdfSignature(path)) {
            throw new BusinessException(
                    PositionErrorCode.FILE_CONTENT_INVALID, "文件实际内容与 TXT 声明不一致");
        }
        var decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        NormalizedLimitedTextWriter writer = new NormalizedLimitedTextWriter(maxCodePoints);
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                Files.newInputStream(path), decoder))) {
            // 当前固定读取缓冲为 1024 个 UTF-16 字符；只影响分块，精确取值依据缺失。
            char[] buffer = new char[1024];
            int read;
            while ((read = reader.read(buffer)) != -1) {
                // 每块先拒绝不允许的控制字符，再交给 Writer 统一换行、空白和 code point 计数。
                rejectBinaryControls(buffer, read);
                writer.write(buffer, 0, read);
            }
        }
        return writer.text();
    }

    /** 在文件前 {@link #SIGNATURE_SCAN_BYTES} 字节内查找标准 PDF 签名。 */
    private boolean hasPdfSignature(Path path) throws IOException {
        byte[] header = new byte[SIGNATURE_SCAN_BYTES];
        int read;
        try (InputStream input = Files.newInputStream(path)) {
            read = input.read(header);
        }
        if (read < PDF_SIGNATURE.length) {
            return false;
        }
        for (int offset = 0; offset <= read - PDF_SIGNATURE.length; offset++) {
            if (matchesAt(header, offset, PDF_SIGNATURE)) {
                return true;
            }
        }
        return false;
    }

    /** 比较缓冲区指定偏移是否完整匹配签名字节，不读取缓冲区外内容。 */
    private boolean matchesAt(byte[] content, int offset, byte[] expected) {
        for (int i = 0; i < expected.length; i++) {
            if (content[offset + i] != expected[i]) {
                return false;
            }
        }
        return true;
    }

    /** 拒绝 TXT 中除制表、换行、回车和换页外的 C0 控制字符以及 DEL。 */
    private void rejectBinaryControls(char[] content, int length) {
        for (int i = 0; i < length; i++) {
            char value = content[i];
            if ((value < 0x20 && value != '\t' && value != '\n'
                    && value != '\r' && value != '\f') || value == 0x7F) {
                throw new BusinessException(
                        PositionErrorCode.FILE_CONTENT_INVALID, "TXT 文件包含无效二进制内容");
            }
        }
    }

    /**
     * 在提取流上完成与 JdContentNormalizer 等价的换行、首尾空白和长度处理，
     * 避免先累积超大模型文本，也避免在规范化前误拒绝合法内容。
     */
    private static final class NormalizedLimitedTextWriter extends Writer {

        /** 允许输出的最大 Unicode code point 数，由上传配置传入。 */
        private final int maxCodePoints;
        /** 已确认属于最终文本的非尾部空白内容。 */
        private final StringBuilder content = new StringBuilder();
        /** 暂存可能属于尾部、最终应丢弃的空白 code point。 */
        private final StringBuilder pendingWhitespace = new StringBuilder();
        /** 已写入 {@link #content} 的 Unicode code point 数。 */
        private int contentCodePoints;
        /** 暂存空白中的 Unicode code point 数。 */
        private int pendingWhitespaceCodePoints;
        /** 是否已经接收首个非空白内容，用于去除前导空白。 */
        private boolean contentStarted;
        /** 是否仍位于第一个有效 code point 之前，用于只删除开头 BOM。 */
        private boolean leadingBom = true;
        /** 上一个字符是否为回车，用于把 CRLF 合并成单个换行。 */
        private boolean skipLineFeed;
        /** 跨写入分块暂存的 UTF-16 高代理项，避免拆分完整 Unicode code point。 */
        private char pendingHighSurrogate;

        private NormalizedLimitedTextWriter(int maxCodePoints) {
            this.maxCodePoints = maxCodePoints;
        }

        /** 接收 PDFBox 或 TXT Reader 的字符块，并逐字符执行代理对和换行规范化。 */
        @Override
        public void write(char[] characters, int offset, int length) throws IOException {
            int end = offset + length;
            for (int index = offset; index < end; index++) {
                acceptCharacter(characters[index]);
            }
        }

        /** 本 Writer 不持有下游资源，因此刷新不产生副作用。 */
        @Override
        public void flush() {
        }

        /** 临时 Writer 的生命周期由当前提取调用管理，关闭不持有或释放外部资源。 */
        @Override
        public void close() {
        }

        /** 返回已规范化文本；末尾空白不并入结果，悬空高代理项按单个 code point 处理。 */
        private String text() throws IOException {
            if (pendingHighSurrogate != 0) {
                acceptCodePoint(pendingHighSurrogate);
                pendingHighSurrogate = 0;
            }
            return content.toString();
        }

        private void acceptCharacter(char value) throws IOException {
            if (pendingHighSurrogate != 0) {
                if (Character.isLowSurrogate(value)) {
                    acceptCodePoint(Character.toCodePoint(pendingHighSurrogate, value));
                    pendingHighSurrogate = 0;
                    return;
                }
                acceptCodePoint(pendingHighSurrogate);
                pendingHighSurrogate = 0;
            }
            if (skipLineFeed) {
                skipLineFeed = false;
                if (value == '\n') {
                    return;
                }
            }
            if (value == '\r') {
                acceptCodePoint('\n');
                skipLineFeed = true;
                return;
            }

            if (Character.isHighSurrogate(value)) {
                pendingHighSurrogate = value;
            } else {
                acceptCodePoint(value);
            }
        }

        private void acceptCodePoint(int codePoint) throws IOException {
            if (leadingBom) {
                if (codePoint == 0xFEFF) {
                    return;
                }
                leadingBom = false;
            }

            if (Character.isWhitespace(codePoint)) {
                if (!contentStarted) {
                    return;
                }
                if (pendingWhitespaceCodePoints <= maxCodePoints - contentCodePoints) {
                    pendingWhitespaceCodePoints++;
                }
                if (contentCodePoints + pendingWhitespaceCodePoints <= maxCodePoints) {
                    pendingWhitespace.appendCodePoint(codePoint);
                }
                return;
            }

            if (contentCodePoints + pendingWhitespaceCodePoints + 1 > maxCodePoints) {
                throw new TextLimitExceededException();
            }
            content.append(pendingWhitespace).appendCodePoint(codePoint);
            contentCodePoints += pendingWhitespaceCodePoints + 1;
            pendingWhitespace.setLength(0);
            pendingWhitespaceCodePoints = 0;
            contentStarted = true;
        }
    }

    /** Writer 内部长度越界信号，由公开提取入口转换为稳定的 JD 过长业务错误。 */
    private static final class TextLimitExceededException extends IOException {
    }
}
