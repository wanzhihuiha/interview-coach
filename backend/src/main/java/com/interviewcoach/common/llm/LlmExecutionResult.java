package com.interviewcoach.common.llm;

import java.util.Objects;

/**
 * 类型化 LLM 调用结果。失败结果只暴露稳定分类，禁止携带或回退使用模型原文。
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
