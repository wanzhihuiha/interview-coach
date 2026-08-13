package com.interviewcoach.position.application.service;

import com.interviewcoach.common.exception.BusinessException;
import org.springframework.stereotype.Component;

/**
 * 统一规范化粘贴输入和文件提取结果中的 JD 文本。
 * HTTP 创建流程与文件提取流程在把文本交给岗位登记事务前调用本组件，得到按 Unicode code point
 * 计数且换行、BOM 和首尾空白已统一的内容；本组件不读取文件，也不写入岗位数据。
 */
@Component
public class JdContentNormalizer {

    /**
     * 规范化并校验一份 JD 文本。
     *
     * <p>处理顺序为移除开头连续 BOM、统一 Windows/旧 Mac 换行、清除首尾空白，再按 Unicode
     * code point 检查上限。空输入或超限会转换为公开业务错误，配置上限非正数则视为服务端配置错误。
     *
     * @param content 来自粘贴请求或文件提取器的原始文本
     * @param maxCodePoints 规范化后允许保留的最大 Unicode code point 数
     * @return 可直接写入岗位记录并交给模型分析的规范化 JD
     */
    public String normalizeAndValidate(String content, int maxCodePoints) {
        if (maxCodePoints <= 0) {
            throw new IllegalStateException("JD 字符上限必须大于 0");
        }
        if (content == null) {
            throw new BusinessException(PositionErrorCode.JD_CONTENT_EMPTY, "JD 描述不能为空");
        }

        // 先统一文本表示，再以最终会持久化和送模的内容执行空值及长度判断。
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
