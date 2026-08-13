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

    /**
     * 返回该定义唯一对应的服务端任务类型，注册器据此建立白名单索引。
     */
    LlmTaskType taskType();

    /**
     * 返回可信服务端参数的运行时类型，安全网关在构造 Prompt 前据此拒绝类型错配。
     */
    Class<P> parametersType();

    /**
     * 返回解析后业务响应的运行时类型，防止调用方用错误 DTO 接收结果。
     */
    Class<O> responseType();

    /**
     * 仅根据可信服务端参数选择或组装固定 system prompt，不得读取 DATA_ONLY 数据块。
     */
    String buildSystemPrompt(P parameters);

    /**
     * 仅根据可信服务端参数构造当前任务要求，外部文本由网关另行写入 DATA_ONLY user message。
     */
    String buildTaskInstruction(P parameters);

    /**
     * 将模型原始响应解析并校验为固定响应类型。
     *
     * <p>结构或内容校验失败只能返回稳定失败分类；实现抛出的异常由网关转换为未预期失败，
     * 任何路径都不能把原文作为业务结果兜底。</p>
     */
    LlmExecutionResult<O> parseResponse(String rawResponse);

    /**
     * 将已解析响应转换为待复检的数据块，模型生成的文本必须全部出现在返回列表中。
     *
     * <p>返回 {@code null}、构造失败或复检命中风险时，网关丢弃成功值并返回失败分类。</p>
     */
    List<LlmDataBlock> buildResponseDataBlocks(O response);
}
