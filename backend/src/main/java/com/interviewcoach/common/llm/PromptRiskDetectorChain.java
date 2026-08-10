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

    public static final String DELEGATE_QUALIFIER = "promptRiskDetectorDelegate";

    private final List<PromptRiskDetector> detectors;

    public PromptRiskDetectorChain(
            @Qualifier(DELEGATE_QUALIFIER) List<PromptRiskDetector> detectors) {
        Objects.requireNonNull(detectors, "Prompt 风险检测器列表不能为空");
        if (detectors.isEmpty()) {
            throw new IllegalStateException("至少需要一个 Prompt 风险检测器");
        }
        this.detectors = List.copyOf(detectors);
    }

    @Override
    public List<PromptRiskSignal> detect(List<LlmDataBlock> dataBlocks) {
        Objects.requireNonNull(dataBlocks, "LLM 数据块列表不能为空");
        List<PromptRiskSignal> signals = new ArrayList<>();
        for (PromptRiskDetector detector : detectors) {
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
