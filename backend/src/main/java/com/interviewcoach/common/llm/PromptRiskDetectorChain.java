package com.interviewcoach.common.llm;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * 按 Spring 注册顺序组合多个 Prompt 风险检测器，并汇总不可变的风险信号快照。
 */
@Component
public class PromptRiskDetectorChain implements PromptRiskDetector {

    /**
     * 默认风险检测器 Bean 使用的限定名，使 Spring 能把各实现收集到本组合器而不注入组合器自身。
     */
    public static final String DELEGATE_QUALIFIER = "promptRiskDetectorDelegate";

    /**
     * Spring 按容器顺序注入的检测器不可变快照，链路会逐个执行且不忽略任一异常。
     */
    private final List<PromptRiskDetector> detectors;

    /**
     * 固化 Spring 收集的检测器列表；没有任何检测器时阻止该组合器完成装配。
     */
    public PromptRiskDetectorChain(
            @Qualifier(DELEGATE_QUALIFIER) List<PromptRiskDetector> detectors) {
        Objects.requireNonNull(detectors, "Prompt 风险检测器列表不能为空");
        if (detectors.isEmpty()) {
            throw new IllegalStateException("至少需要一个 Prompt 风险检测器");
        }
        this.detectors = List.copyOf(detectors);
    }

    /**
     * 按 Spring 注入顺序逐个执行检测器并返回不可变合并结果。
     *
     * <p>任一检测器抛出异常或返回 {@code null} 都会终止链路，异常继续交给安全网关失败关闭。</p>
     */
    @Override
    public List<PromptRiskSignal> detect(List<LlmDataBlock> dataBlocks) {
        Objects.requireNonNull(dataBlocks, "LLM 数据块列表不能为空");
        List<PromptRiskSignal> signals = new ArrayList<>();
        for (PromptRiskDetector detector : detectors) {
            // 每个检测器都必须看到同一批数据；局部失败不能被忽略后继续模型调用。
            List<PromptRiskSignal> detected = detector.detect(dataBlocks);
            if (detected == null) {
                throw new IllegalStateException(
                        "Prompt 风险检测器返回空列表: " + detector.getClass().getSimpleName());
            }
            signals.addAll(detected);
        }
        return List.copyOf(signals);
    }
}
