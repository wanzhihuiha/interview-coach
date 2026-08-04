package com.interviewcoach.position.application.service;

import com.interviewcoach.common.exception.BusinessException;
import org.springframework.stereotype.Component;

/**
 * 统一规范化粘贴与文件提取的 JD，并按 Unicode code point 执行业务长度限制。
 */
@Component
public class JdContentNormalizer {

    public String normalizeAndValidate(String content, int maxCodePoints) {
        if (maxCodePoints <= 0) {
            throw new IllegalStateException("JD 字符上限必须大于 0");
        }
        if (content == null) {
            throw new BusinessException(PositionErrorCode.JD_CONTENT_EMPTY, "JD 描述不能为空");
        }

        String normalized = removeLeadingBom(content)
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .strip();
        if (normalized.isEmpty()) {
            throw new BusinessException(PositionErrorCode.JD_CONTENT_EMPTY, "JD 描述不能为空");
        }
        if (normalized.codePointCount(0, normalized.length()) > maxCodePoints) {
            throw new BusinessException(
                    PositionErrorCode.JD_CONTENT_TOO_LONG,
                    "JD 描述不能超过 " + maxCodePoints + " 个字符");
        }
        return normalized;
    }

    private String removeLeadingBom(String content) {
        int start = 0;
        while (start < content.length() && content.charAt(start) == '\uFEFF') {
            start++;
        }
        return start == 0 ? content : content.substring(start);
    }
}
