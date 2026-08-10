package com.interviewcoach.common.llm;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * 注册服务端允许执行的 LLM 任务定义；重复任务类型会在 Spring 启动装配阶段直接失败。
 */
@Component
public class LlmTaskDefinitionRegistry {

    private final Map<LlmTaskType, LlmTaskDefinition<?, ?>> definitions;

    public LlmTaskDefinitionRegistry(List<LlmTaskDefinition<?, ?>> taskDefinitions) {
        Objects.requireNonNull(taskDefinitions, "LLM 任务定义列表不能为空");
        if (taskDefinitions.isEmpty()) {
            throw new IllegalStateException("至少需要一个 LLM 任务定义");
        }
        Map<LlmTaskType, LlmTaskDefinition<?, ?>> registered = new EnumMap<>(LlmTaskType.class);
        for (LlmTaskDefinition<?, ?> definition : taskDefinitions) {
            Objects.requireNonNull(definition, "LLM 任务定义不能为空");
            LlmTaskDefinition<?, ?> previous = registered.putIfAbsent(
                    Objects.requireNonNull(definition.taskType(), "LLM 任务类型不能为空"),
                    definition);
            if (previous != null) {
                throw new IllegalStateException(
                        "LLM 任务类型重复注册: " + definition.taskType());
            }
        }
        definitions = Map.copyOf(registered);
    }

    public Optional<LlmTaskDefinition<?, ?>> find(LlmTaskType taskType) {
        return Optional.ofNullable(definitions.get(taskType));
    }
}
