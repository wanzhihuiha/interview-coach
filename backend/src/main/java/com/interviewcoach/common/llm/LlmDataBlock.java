package com.interviewcoach.common.llm;

import java.util.Objects;

/**
 * 发送给 LLM 的单个 DATA_ONLY 数据块。
 *
 * <p>{@code blockId} 由服务端生成并在单次请求内保持稳定。{@code text} 只能作为待处理数据，
 * 不能拼接到 system prompt，也不能改变任务类型、流程或权限。该对象由业务 Agent 组装，
 * 经安全网关检测和 JSON 隔离后交给底层模型传输。</p>
 *
 * @param blockId 单次请求内唯一的服务端数据块标识，用于风险信号定位
 * @param source 数据的业务来源标签，仅供检测、路由和审计，不授予指令权限
 * @param text 可能包含简历、岗位或回答等敏感内容的不可信原文
 */
public record LlmDataBlock(String blockId, LlmDataSource source, String text) {

    /**
     * 单个数据块允许的最大 Unicode 码点数。
     *
     * <p>8000 来自面试模块安全入口的输入限制；提高会扩大单块模型输入与检测负担，
     * 降低则会让更短的业务文本在构造阶段被拒绝。</p>
     */
    public static final int MAX_TEXT_CHARACTERS = 8_000;

    /**
     * 固化数据块时校验标识、来源和正文，并在正文超过单块上限时拒绝构造。
     */
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

    /**
     * 按 Unicode 码点返回正文长度，供单块与整次请求的输入限制共同计数。
     */
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
