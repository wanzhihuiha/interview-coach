package com.interviewcoach.position.infrastructure.parser;

import com.interviewcoach.common.exception.BusinessException;
import com.interviewcoach.position.application.service.PositionErrorCode;
import java.util.Locale;

/**
 * 岗位 JD 允许的文件类型；真实内容仍由后端提取器再次校验。
 */
public enum JdFileType {
    PDF(".pdf"),
    TXT(".txt");

    private final String temporarySuffix;

    JdFileType(String temporarySuffix) {
        this.temporarySuffix = temporarySuffix;
    }

    public String temporarySuffix() {
        return temporarySuffix;
    }

    public static JdFileType from(String value) {
        if (value == null || value.isBlank()) {
            throw new BusinessException(
                    PositionErrorCode.FILE_TYPE_NOT_SUPPORTED, "仅支持 PDF 和 TXT 格式");
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BusinessException(
                    PositionErrorCode.FILE_TYPE_NOT_SUPPORTED, "仅支持 PDF 和 TXT 格式");
        }
    }
}
