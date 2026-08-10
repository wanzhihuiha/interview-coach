package com.interviewcoach.common.llm;

import java.util.List;

/**
 * 一个受控 LLM 任务的服务端定义。
 *
 * <p>实现类只能接收 {@link LlmTaskInput#parameters()} 中由服务端决定的类型化参数，并据此选择固定
 * 模板；它不能接收 {@link LlmTaskInput#dataBlocks()}。简历、JD、回答、用户反馈和历史模型结果由
 * 统一入口独立组装为 DATA_ONLY user message，从接口层阻止外部文本进入 system prompt。</p>
 *
 * @param <P> 服务端控制的任务参数类型
 * @param <O> 已校验的任务响应类型
 */
public interface LlmTaskDefinition<P, O> {

    LlmTaskType taskType();

    Class<P> parametersType();

    Class<O> responseType();

    String buildSystemPrompt(P parameters);

    String buildTaskInstruction(P parameters);

    /**
     * 将模型原始响应解析并校验为固定响应类型；无效响应只能返回失败分类，不能返回原文兜底。
     */
    LlmExecutionResult<O> parseResponse(String rawResponse);

    /**
     * 将已解析响应转换为待复检的数据块。模型生成的文本必须全部出现在返回列表中。
     */
    List<LlmDataBlock> buildResponseDataBlocks(O response);
}
