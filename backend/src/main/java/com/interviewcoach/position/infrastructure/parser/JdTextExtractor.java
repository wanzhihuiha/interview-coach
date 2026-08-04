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
 * 从服务端临时文件提取受限 JD 文本，不持有或保存上传原文件。
 */
@Slf4j
@Component
public class JdTextExtractor {

    private static final byte[] PDF_SIGNATURE = "%PDF-".getBytes(StandardCharsets.US_ASCII);
    private static final int SIGNATURE_SCAN_BYTES = 1024;

    /**
     * 校验真实文件特征并提取最多 maxCodePoints 个 Unicode 完整字符。
     */
    public String extract(Path path, JdFileType fileType, int maxPdfPages, int maxCodePoints) {
        try {
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

    private String extractPdf(Path path, int maxPdfPages, int maxCodePoints) throws IOException {
        if (!hasPdfSignature(path)) {
            throw new BusinessException(
                    PositionErrorCode.FILE_CONTENT_INVALID, "文件实际内容不是 PDF");
        }
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
            new PDFTextStripper().writeText(document, writer);
            return writer.text();
        }
    }

    private String extractTxt(Path path, int maxCodePoints) throws IOException {
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
            char[] buffer = new char[1024];
            int read;
            while ((read = reader.read(buffer)) != -1) {
                rejectBinaryControls(buffer, read);
                writer.write(buffer, 0, read);
            }
        }
        return writer.text();
    }

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

    private boolean matchesAt(byte[] content, int offset, byte[] expected) {
        for (int i = 0; i < expected.length; i++) {
            if (content[offset + i] != expected[i]) {
                return false;
            }
        }
        return true;
    }

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

        private final int maxCodePoints;
        private final StringBuilder content = new StringBuilder();
        private final StringBuilder pendingWhitespace = new StringBuilder();
        private int contentCodePoints;
        private int pendingWhitespaceCodePoints;
        private boolean contentStarted;
        private boolean leadingBom = true;
        private boolean skipLineFeed;
        private char pendingHighSurrogate;

        private NormalizedLimitedTextWriter(int maxCodePoints) {
            this.maxCodePoints = maxCodePoints;
        }

        @Override
        public void write(char[] characters, int offset, int length) throws IOException {
            int end = offset + length;
            for (int index = offset; index < end; index++) {
                acceptCharacter(characters[index]);
            }
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }

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

    private static final class TextLimitExceededException extends IOException {
    }
}
