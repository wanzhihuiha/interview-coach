package com.interviewcoach.common.llm;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 一次类型化 LLM 调用的公共输入。
 *
 * <p>{@code parameters} 只能包含服务端决定的任务参数；所有外部文本必须放入 {@code dataBlocks}。
 * 构造时固化数据块快照并校验统一上限，避免调用方在安全检查后继续修改内容。</p>
 *
 * @param <P> 服务端控制的任务参数类型
 */
public record LlmTaskInput<P>(
        LlmTaskType taskType,
        P parameters,
        List<LlmDataBlock> dataBlocks) {

    public static final int MAX_DATA_BLOCKS = 16;
    public static final int MAX_TOTAL_CHARACTERS = 12_000;

    public LlmTaskInput {
        Objects.requireNonNull(taskType, "LLM 任务类型不能为空");
        Objects.requireNonNull(parameters, "LLM 任务参数不能为空");
        Objects.requireNonNull(dataBlocks, "LLM 数据块列表不能为空");
        if (dataBlocks.size() > MAX_DATA_BLOCKS) {
            throw new IllegalArgumentException(
                    "LLM 单次请求不能超过 " + MAX_DATA_BLOCKS + " 个数据块");
        }

        List<LlmDataBlock> snapshot = List.copyOf(dataBlocks);
        Set<String> blockIds = new HashSet<>();
        int totalCharacters = 0;
        for (LlmDataBlock dataBlock : snapshot) {
            if (!blockIds.add(dataBlock.blockId())) {
                throw new IllegalArgumentException("LLM 数据块 ID 不能重复: " + dataBlock.blockId());
            }
            totalCharacters += dataBlock.characterCount();
        }
        if (totalCharacters > MAX_TOTAL_CHARACTERS) {
            throw new IllegalArgumentException(
                    "LLM 单次请求数据不能超过 " + MAX_TOTAL_CHARACTERS + " 个 Unicode 字符");
        }
        dataBlocks = snapshot;
    }

    public int totalCharacterCount() {
        return dataBlocks.stream().mapToInt(LlmDataBlock::characterCount).sum();
    }

    /**
     * 输入对象只输出任务类型和规模，不输出数据块内容或标识。
     */
    @Override
    public String toString() {
        return "LlmTaskInput[taskType=" + taskType
                + ", dataBlockCount=" + dataBlocks.size()
                + ", totalCharacterCount=" + totalCharacterCount() + "]";
    }
}
