package com.interviewcoach.resume.infrastructure.ai;

import com.interviewcoach.resume.infrastructure.ai.config.LlmProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 模型路由器，根据任务层级选择主用厂商/模型，主用失败时返回备用厂商/模型。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ModelRouter {

    private final LlmProperties llmProperties;

    /**
     * 根据层级选择主用厂商和模型。
     */
    public VendorModel selectPrimary(ModelTier tier) {
        LlmProperties.VendorModelConfig config = getTierConfig(tier);
        return resolveVendorModel(config.getPrimary());
    }

    /**
     * 根据层级选择备用厂商和模型。
     */
    public VendorModel selectFallback(ModelTier tier) {
        LlmProperties.VendorModelConfig config = getTierConfig(tier);
        return resolveVendorModel(config.getFallback());
    }

    /**
     * 记录模型切换日志。
     */
    public void recordFallback(ModelTier tier, VendorModel from, VendorModel to, Throwable cause) {
        log.warn("[ModelRouter] 模型降级: tier={}, from={}/{}, to={}/{}, reason={}",
                tier, from.getVendorKey(), from.getModel(), to.getVendorKey(), to.getModel(), cause.getMessage());
    }

    private LlmProperties.VendorModelConfig getTierConfig(ModelTier tier) {
        return switch (tier) {
            case L1 -> llmProperties.getTiers().getL1();
            case L2 -> llmProperties.getTiers().getL2();
            case L3 -> llmProperties.getTiers().getL3();
        };
    }

    /**
     * 解析 vendorKey 或 vendorKey:model 格式的配置。
     */
    private VendorModel resolveVendorModel(String expression) {
        if (expression == null || expression.isBlank()) {
            throw new IllegalArgumentException("模型路由配置不能为空");
        }
        String[] parts = expression.split(":", 2);
        String vendorKey = parts[0].trim();
        LlmProperties.VendorConfig vendorConfig = llmProperties.getVendors().get(vendorKey);
        if (vendorConfig == null) {
            throw new IllegalArgumentException("未找到厂商配置: " + vendorKey);
        }
        if (!vendorConfig.isEnabled()) {
            throw new IllegalArgumentException("厂商未启用: " + vendorKey);
        }
        VendorType vendorType = parseVendorType(vendorConfig.getType());
        String model = parts.length > 1 ? parts[1].trim() : vendorConfig.getModel();
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("模型名称不能为空: " + vendorKey);
        }
        return new VendorModel(vendorKey, vendorType, vendorConfig, model);
    }

    private VendorType parseVendorType(String type) {
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("厂商类型不能为空");
        }
        try {
            return VendorType.valueOf(type.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("不支持的厂商类型: " + type);
        }
    }
}
