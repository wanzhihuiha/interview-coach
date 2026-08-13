package com.interviewcoach.position.infrastructure.parser;

import com.interviewcoach.common.exception.BusinessException;
import com.interviewcoach.position.application.service.PositionErrorCode;
import java.util.Locale;

/**
 * 岗位 JD 允许的文件类型；真实内容仍由后端提取器再次校验。
 */
public enum JdFileType {
    /** PDF 岗位文档；提取前还会校验文件签名、加密状态和页数。 */
    PDF(".pdf"),

    /** 严格 UTF-8 编码的纯文本岗位文档；提取时拒绝 PDF 签名和二进制控制字符。 */
    TXT(".txt");

    /** 服务端创建随机临时文件时使用的受控后缀，不采信客户端原文件名。 */
    private final String temporarySuffix;

    JdFileType(String temporarySuffix) {
        this.temporarySuffix = temporarySuffix;
    }

    /**
     * 返回当前受支持类型对应的临时文件后缀。
     *
     * @return {@code .pdf} 或 {@code .txt}
     */
    public String temporarySuffix() {
        return temporarySuffix;
    }

    /**
     * 把 HTTP 参数按去空白、忽略大小写的稳定枚举名转换为文件类型。
     *
     * @param value 调用方提交的 {@code PDF} 或 {@code TXT} 文本
     * @return 对应的受支持文件类型
     * @throws BusinessException 参数为空或不在受支持集合时抛出文件类型错误
     */
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
