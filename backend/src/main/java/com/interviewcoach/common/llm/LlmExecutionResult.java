package com.interviewcoach.common.llm;

import java.util.Objects;

/**
 * 任务定义和安全网关创建、业务 Agent 最终消费的类型化 LLM 调用结果。
 *
 * <p>任务定义可先用成功分支表示“结构与内容已解析”，安全网关还会对其中模型文本执行输出复检；
 * 只有网关最终返回的成功值才可交给业务 Agent。失败分支只暴露稳定分类，禁止携带或回退使用
 * 模型原文。</p>
 */
public sealed interface LlmExecutionResult<T>
        permits LlmExecutionResult.Success, LlmExecutionResult.Failure {

    boolean isSuccess();

    default boolean isFailure() {
        return !isSuccess();
    }

    static <T> LlmExecutionResult<T> success(T value) {
        return new Success<>(value);
    }

    static <T> LlmExecutionResult<T> failure(LlmFailureType failureType) {
        return new Failure<>(failureType);
    }

    /**
     * 表示当前处理阶段已经产生非空类型化值；任务定义解析阶段的成功值仍须由安全网关完成输出复检。
     *
     * @param value 交给业务 Agent 使用的非空类型化结果
     */
    record Success<T>(T value) implements LlmExecutionResult<T> {

        public Success {
            Objects.requireNonNull(value, "LLM 成功结果不能为空");
        }

        @Override
        public boolean isSuccess() {
            return true;
        }

        /**
         * 成功值可能包含模型生成的敏感文本，默认日志表示不展开内容。
         */
        @Override
        public String toString() {
            return "LlmExecutionResult.Success[value=<redacted>]";
        }
    }

    /**
     * 表示安全网关未产生可供业务使用的模型结果，且不携带供应商或模型原文。
     *
     * @param failureType 供业务降级和受控重试判断的稳定失败分类
     */
    record Failure<T>(LlmFailureType failureType) implements LlmExecutionResult<T> {

        public Failure {
            Objects.requireNonNull(failureType, "LLM 失败类型不能为空");
        }

        @Override
        public boolean isSuccess() {
            return false;
        }
    }
}
