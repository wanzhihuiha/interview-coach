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

    /**
     * 以任务类型为键、对应类型化定义为值的不可变白名单，供安全网关查找任务契约。
     */
    private final Map<LlmTaskType, LlmTaskDefinition<?, ?>> definitions;

    /**
     * 接收 Spring 收集的全部任务定义并建立不可变索引。
     *
     * <p>列表为空、成员为空、任务类型为空或同一任务类型重复注册都会使 Bean 创建失败，
     * 从而终止应用上下文装配。</p>
     */
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

    /**
     * 按服务端任务类型查找定义；未注册时返回空值，由安全网关归类为非法请求。
     */
    public Optional<LlmTaskDefinition<?, ?>> find(LlmTaskType taskType) {
        return Optional.ofNullable(definitions.get(taskType));
    }
}
