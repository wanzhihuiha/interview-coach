package com.interviewcoach.common.llm;

import java.util.Objects;

/**
 * 发送给 LLM 的单个 DATA_ONLY 数据块。
 *
 * <p>{@code blockId} 由服务端生成并在单次请求内保持稳定。{@code text} 只能作为待处理数据，
 * 不能拼接到 system prompt，也不能改变任务类型、流程或权限。</p>
 */
public record LlmDataBlock(String blockId, LlmDataSource source, String text) {

    public static final int MAX_TEXT_CHARACTERS = 8_000;

    public LlmDataBlock {
        if (blockId == null || blockId.isBlank()) {
            throw new IllegalArgumentException("LLM 数据块 ID 不能为空");
        }
        Objects.requireNonNull(source, "LLM 数据块来源不能为空");
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("LLM 数据块内容不能为空");
        }
        int characterCount = text.codePointCount(0, text.length());
        if (characterCount > MAX_TEXT_CHARACTERS) {
            throw new IllegalArgumentException(
                    "LLM 单个数据块不能超过 " + MAX_TEXT_CHARACTERS + " 个 Unicode 字符");
        }
    }

    public int characterCount() {
        return text.codePointCount(0, text.length());
    }

    /**
     * 避免记录对象时把简历、JD 或回答原文带入日志。
     */
    @Override
    public String toString() {
        return "LlmDataBlock[blockId=" + blockId
                + ", source=" + source
                + ", text=<redacted>, characterCount=" + characterCount() + "]";
    }
}
